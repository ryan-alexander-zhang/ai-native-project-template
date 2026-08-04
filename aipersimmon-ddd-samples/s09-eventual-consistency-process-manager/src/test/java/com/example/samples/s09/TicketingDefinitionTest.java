package com.example.samples.s09;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.processmanager.definition.ProcessContext;
import com.aipersimmon.ddd.processmanager.definition.ProcessDecision;
import com.aipersimmon.ddd.processmanager.effect.CancelDeadline;
import com.aipersimmon.ddd.processmanager.effect.DispatchCommand;
import com.aipersimmon.ddd.processmanager.effect.ScheduleDeadline;
import com.aipersimmon.ddd.processmanager.model.DefinitionVersion;
import com.aipersimmon.ddd.processmanager.model.ProcessBusinessKey;
import com.aipersimmon.ddd.processmanager.model.ProcessInstanceId;
import com.aipersimmon.ddd.processmanager.model.ProcessLifecycle;
import com.aipersimmon.ddd.processmanager.model.ProcessRef;
import com.aipersimmon.ddd.processmanager.model.ProcessRevision;
import com.aipersimmon.ddd.processmanager.model.ProcessStep;
import com.aipersimmon.ddd.tenancy.Tenants;
import com.example.samples.s09.ticketing.application.ChargeWallet;
import com.example.samples.s09.ticketing.application.HoldSeat;
import com.example.samples.s09.ticketing.application.fulfilment.TicketingDefinition;
import com.example.samples.s09.ticketing.application.fulfilment.TicketingInput;
import com.example.samples.s09.ticketing.application.fulfilment.TicketingState;
import com.example.samples.s09.ticketing.domain.OrderStatus;
import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The flow, tested with no Spring, no database and no clock — which is the dividend the library charges a
 * price for.
 *
 * <p>The price is that a {@code ProcessDefinition} may do no I/O: it cannot read the order it is
 * coordinating, so everything a later step needs has to be carried in the state. The dividend is this
 * class. It runs in milliseconds, it can put the flow in any state including ones a real run reaches once
 * a month, and it needs no fixture beyond a context record.
 */
class TicketingDefinitionTest {

  private static final Instant NOW = Instant.parse("2026-08-04T10:00:00Z");

  private final TicketingDefinition definition =
      new TicketingDefinition(Duration.ofSeconds(30), Duration.ofSeconds(60));

  @Test
  void thestartAsksForASeatAndArmsATimerInTheSameDecision() {
    ProcessDecision<TicketingState> decision =
        definition.start(
            new TicketingInput.OrderPlaced("order-1", "customer-1", "STALLS", 4500),
            contextForStart());

    assertThat(decision.lifecycle()).isEqualTo(ProcessLifecycle.RUNNING);
    assertThat(decision.step()).isEqualTo(new ProcessStep("AWAITING_SEAT"));
    // Two effects, and the second is what makes the first survivable. Both are staged in the transition's
    // own transaction and delivered afterwards, in this order.
    assertThat(decision.effects()).hasSize(2);
    assertThat(decision.effects().get(0)).isInstanceOf(DispatchCommand.class);
    assertThat(((DispatchCommand) decision.effects().get(0)).command())
        .isEqualTo(new HoldSeat("order-1", "STALLS"));
    ScheduleDeadline deadline = (ScheduleDeadline) decision.effects().get(1);
    assertThat(deadline.dueAt()).isEqualTo(NOW.plusSeconds(30));
  }

  @Test
  void theseatHeldFactCancelsTheTimerBeforeAskingForTheMoney() {
    ProcessDecision<TicketingState> decision =
        definition.react(
            awaiting(TicketingState.Step.AWAITING_SEAT),
            new TicketingInput.SeatHeld("order-1"),
            contextAt("AWAITING_SEAT", ProcessLifecycle.RUNNING));

    // Cancel first, then dispatch. Order matters on replay: a redelivered batch must not re-arm a timer for
    // a step that has moved on.
    assertThat(decision.effects().get(0)).isInstanceOf(CancelDeadline.class);
    assertThat(((DispatchCommand) decision.effects().get(1)).command())
        .isEqualTo(new ChargeWallet("order-1", "customer-1", 4500));
  }

  @Test
  void aduplicateFactIsAbsorbedRatherThanThrown() {
    // The runtime is at-least-once, so this is not an edge case, it is Tuesday. Throwing would be read as a
    // poison message and retried; absorbing it keeps the flow where it is and records what happened.
    ProcessDecision<TicketingState> decision =
        definition.react(
            awaiting(TicketingState.Step.AWAITING_TICKET),
            new TicketingInput.SeatHeld("order-1"),
            contextAt("AWAITING_TICKET", ProcessLifecycle.RUNNING));

    assertThat(decision.effects()).isEmpty();
    assertThat(decision.step()).isEqualTo(new ProcessStep("AWAITING_TICKET"));
    assertThat(decision.decisionCode().value()).isEqualTo("ignored:AWAITING_TICKET:SeatHeld");
  }

  @Test
  void acancellationRequestIsRememberedRatherThanActedOnWhileAStepIsInFlight() {
    ProcessDecision<TicketingState> decision =
        definition.react(
            awaiting(TicketingState.Step.AWAITING_SEAT),
            new TicketingInput.CancellationRequested("order-1", "changed my mind"),
            contextAt("AWAITING_SEAT", ProcessLifecycle.RUNNING));

    // Same step, no effects, one flag. Acting immediately would leave the in-flight HoldSeat to land
    // afterwards — a seat held for an order nobody is coordinating any more.
    assertThat(decision.step()).isEqualTo(new ProcessStep("AWAITING_SEAT"));
    assertThat(decision.effects()).isEmpty();
    assertThat(decision.state().cancellationRequested()).isTrue();
  }

  @Test
  void acancellationRequestAfterTheTicketStepIsRefusedNotDeferred() {
    ProcessDecision<TicketingState> decision =
        definition.react(
            awaiting(TicketingState.Step.AWAITING_TICKET),
            new TicketingInput.CancellationRequested("order-1", "too late"),
            contextAt("AWAITING_TICKET", ProcessLifecycle.RUNNING));

    assertThat(decision.effects()).isEmpty();
    assertThat(decision.state().cancellationRequested())
        .as("the flag is not even set: this is the point of no return, not a deferral")
        .isFalse();
  }

  @Test
  void theprocessStateHoldsNoCopyOfTheOrdersStatus() {
    // The catalogue's hardest question, asserted structurally rather than argued. A flow may remember
    // facts (the seat class, the amount, the debit reference — none of which can change behind its back)
    // and may never remember a conclusion the aggregate owns.
    RecordComponent[] components = TicketingState.class.getRecordComponents();

    assertThat(Arrays.stream(components).map(RecordComponent::getType))
        .doesNotContain(OrderStatus.class);
    assertThat(Arrays.stream(components).map(RecordComponent::getName))
        .doesNotContain("status", "orderStatus");
  }

  @Test
  void adecisionWhoseStepDisagreesWithItsStateIsRefusedByTheLibrary() {
    TicketingState state = awaiting(TicketingState.Step.AWAITING_PAYMENT);

    // The persisted step is a column and the state is an encoded blob, so nothing downstream could
    // reconcile them once they diverged. HasStep is what makes this a construction-time failure.
    assertThatThrownBy(
            () ->
                new ProcessDecision<>(
                    state,
                    ProcessLifecycle.RUNNING,
                    new ProcessStep("AWAITING_TICKET"),
                    Optional.empty(),
                    new com.aipersimmon.ddd.processmanager.model.DecisionCode("wrong"),
                    java.util.List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("disagrees with the state's own");
  }

  private static TicketingState awaiting(TicketingState.Step step) {
    return new TicketingState(
        "order-1", "customer-1", "STALLS", 4500, step, step != TicketingState.Step.AWAITING_SEAT,
        step == TicketingState.Step.AWAITING_TICKET ? "ticket-debit:order-1" : null, false, null);
  }

  private static ProcessContext contextForStart() {
    return new ProcessContext(
        ref(),
        new ProcessRevision(0),
        DefinitionVersion.INITIAL,
        Optional.empty(),
        Optional.empty(),
        NOW,
        CommandContext.root(Tenants.ROOT, "message-1"));
  }

  private static ProcessContext contextAt(String step, ProcessLifecycle lifecycle) {
    return new ProcessContext(
        ref(),
        new ProcessRevision(1),
        DefinitionVersion.INITIAL,
        Optional.of(lifecycle),
        Optional.of(new ProcessStep(step)),
        NOW,
        CommandContext.root(Tenants.ROOT, "message-2"));
  }

  private static ProcessRef ref() {
    return new ProcessRef(
        new ProcessInstanceId("instance-1"),
        TicketingDefinition.PROCESS_TYPE,
        new ProcessBusinessKey("order-1"));
  }
}
