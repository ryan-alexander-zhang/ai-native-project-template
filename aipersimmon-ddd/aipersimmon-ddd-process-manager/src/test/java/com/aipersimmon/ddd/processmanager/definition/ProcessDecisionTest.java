package com.aipersimmon.ddd.processmanager.definition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.aipersimmon.ddd.processmanager.effect.CancelDeadline;
import com.aipersimmon.ddd.processmanager.effect.ProcessEffect;
import com.aipersimmon.ddd.processmanager.effect.ScheduleDeadline;
import com.aipersimmon.ddd.processmanager.model.DeadlineName;
import com.aipersimmon.ddd.processmanager.model.DecisionCode;
import com.aipersimmon.ddd.processmanager.model.ProcessLifecycle;
import com.aipersimmon.ddd.processmanager.model.ProcessOutcome;
import com.aipersimmon.ddd.processmanager.model.ProcessStep;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The Decision invariants that need no runtime context. */
class ProcessDecisionTest {

  record State(String value) {}

  record DeadlineInput(String value) implements ProcessInput {}

  /** A state that knows its step, as the factories expect. */
  record SteppedState(String value, ProcessStep atStep) implements HasStep {
    @Override
    public ProcessStep processStep() {
      return atStep;
    }
  }

  private static final DecisionCode CODE = new DecisionCode("decided");
  private static final ProcessStep STEP = new ProcessStep("AWAITING_STOCK");

  @Test
  void runningDecisionWithoutOutcomeIsValid() {
    var decision =
        new ProcessDecision<>(
            new State("s"), ProcessLifecycle.RUNNING, STEP, Optional.empty(), CODE, List.of());
    assertEquals(ProcessLifecycle.RUNNING, decision.lifecycle());
  }

  @Test
  void terminalDecisionWithOutcomeIsValid() {
    var decision =
        new ProcessDecision<>(
            new State("s"),
            ProcessLifecycle.COMPLETED,
            STEP,
            Optional.of(new ProcessOutcome("ORDER_CONFIRMED")),
            CODE,
            List.of());
    assertEquals(ProcessLifecycle.COMPLETED, decision.lifecycle());
  }

  @Test
  void definitionMayNotReturnSuspended() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ProcessDecision<>(
                new State("s"),
                ProcessLifecycle.SUSPENDED,
                STEP,
                Optional.empty(),
                CODE,
                List.of()));
  }

  @Test
  void terminalLifecycleRequiresOutcome() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ProcessDecision<>(
                new State("s"), ProcessLifecycle.FAILED, STEP, Optional.empty(), CODE, List.of()));
  }

  @Test
  void nonTerminalLifecycleRejectsOutcome() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ProcessDecision<>(
                new State("s"),
                ProcessLifecycle.RUNNING,
                STEP,
                Optional.of(new ProcessOutcome("x")),
                CODE,
                List.of()));
  }

  @Test
  void schedulingAndCancellingTheSameDeadlineInOneDecisionIsAmbiguous() {
    DeadlineName name = new DeadlineName("REVIEW");
    List<ProcessEffect> effects =
        List.of(
            new ScheduleDeadline(name, Instant.EPOCH, new DeadlineInput("d")),
            new CancelDeadline(name));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ProcessDecision<>(
                new State("s"), ProcessLifecycle.RUNNING, STEP, Optional.empty(), CODE, effects));
  }

  @Test
  void distinctDeadlineNamesAreFine() {
    List<ProcessEffect> effects =
        List.of(
            new ScheduleDeadline(new DeadlineName("REVIEW"), Instant.EPOCH, new DeadlineInput("d")),
            new CancelDeadline(new DeadlineName("PAYMENT")));
    var decision =
        new ProcessDecision<>(
            new State("s"), ProcessLifecycle.RUNNING, STEP, Optional.empty(), CODE, effects);
    assertEquals(2, decision.effects().size());
  }

  @Test
  void effectsAreDefensivelyCopiedAndUnmodifiable() {
    List<ProcessEffect> mutable = new ArrayList<>();
    mutable.add(new CancelDeadline(new DeadlineName("REVIEW")));
    var decision =
        new ProcessDecision<>(
            new State("s"), ProcessLifecycle.RUNNING, STEP, Optional.empty(), CODE, mutable);

    mutable.add(new CancelDeadline(new DeadlineName("PAYMENT")));
    assertEquals(
        1, decision.effects().size(), "later mutation of the source list must not leak in");
    assertThrows(
        UnsupportedOperationException.class,
        () -> decision.effects().add(new CancelDeadline(new DeadlineName("X"))));
  }

  // --- the factories: state says its step once, the decision fills in the rest ------------------

  @Test
  void theRunningFactoryReadsTheStepFromTheState() {
    var decision = ProcessDecision.running(new SteppedState("s", STEP), "stock-reserved");

    assertEquals(ProcessLifecycle.RUNNING, decision.lifecycle());
    assertEquals(STEP, decision.step());
    assertEquals(new DecisionCode("stock-reserved"), decision.decisionCode());
    assertEquals(Optional.empty(), decision.outcome());
    assertEquals(List.of(), decision.effects());
  }

  @Test
  void theCompensatingFactoryReadsTheStepFromTheState() {
    var decision =
        ProcessDecision.compensating(
            new SteppedState("s", STEP),
            "payment-declined",
            new CancelDeadline(new DeadlineName("PAYMENT")));

    assertEquals(ProcessLifecycle.COMPENSATING, decision.lifecycle());
    assertEquals(STEP, decision.step());
    assertEquals(1, decision.effects().size());
  }

  @Test
  void theCompletedFactoryCarriesItsOutcome() {
    var decision =
        ProcessDecision.completed(
            new SteppedState("s", STEP), "order-confirmed", "ORDER_CONFIRMED");

    assertEquals(ProcessLifecycle.COMPLETED, decision.lifecycle());
    assertEquals(Optional.of(new ProcessOutcome("ORDER_CONFIRMED")), decision.outcome());
  }

  @Test
  void ignoredKeepsTheCurrentLifecycleAndStepAndEmitsNothing() {
    ProcessContext context = reactContext(ProcessLifecycle.COMPENSATING, STEP);

    var decision = ProcessDecision.ignored(context, new State("unchanged"), new DeadlineInput("d"));

    // A duplicate or out-of-order input must be absorbed, not throw: the runtime retries a react
    // throw forever. Same lifecycle, same step, no effects — and the audit trail names the absorbed
    // input without the definition composing strings.
    assertEquals(ProcessLifecycle.COMPENSATING, decision.lifecycle());
    assertEquals(STEP, decision.step());
    assertEquals(List.of(), decision.effects());
    assertEquals(new DecisionCode("ignored:AWAITING_STOCK:DeadlineInput"), decision.decisionCode());
  }

  @Test
  void ignoredRefusesAStartContext() {
    ProcessContext start = startContext();

    assertThrows(
        IllegalArgumentException.class,
        () -> ProcessDecision.ignored(start, new State("s"), new DeadlineInput("d")));
  }

  /**
   * The double-write guard: the step column and the step inside the encoded state are persisted
   * separately, so a decision whose two answers disagree must be refused at construction — nothing
   * downstream can reconcile them.
   */
  @Test
  void aStateThatKnowsItsStepMayNotBePersistedUnderADifferentOne() {
    IllegalArgumentException refused =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new ProcessDecision<>(
                    new SteppedState("s", new ProcessStep("AWAITING_PAYMENT")),
                    ProcessLifecycle.RUNNING,
                    STEP,
                    Optional.empty(),
                    CODE,
                    List.of()));
    assertEquals(
        true,
        refused.getMessage().contains("disagrees with the state's own"),
        refused.getMessage());
  }

  private static ProcessContext reactContext(ProcessLifecycle lifecycle, ProcessStep step) {
    return new ProcessContext(
        new com.aipersimmon.ddd.processmanager.model.ProcessRef(
            new com.aipersimmon.ddd.processmanager.model.ProcessInstanceId("i-1"),
            new com.aipersimmon.ddd.processmanager.model.ProcessType("Ordering"),
            new com.aipersimmon.ddd.processmanager.model.ProcessBusinessKey("order-1")),
        new com.aipersimmon.ddd.processmanager.model.ProcessRevision(1),
        com.aipersimmon.ddd.processmanager.model.DefinitionVersion.INITIAL,
        Optional.of(lifecycle),
        Optional.of(step),
        Instant.EPOCH,
        com.aipersimmon.ddd.cqrs.CommandContext.root(
            com.aipersimmon.ddd.tenancy.Tenants.of("acme"), "m-1"));
  }

  private static ProcessContext startContext() {
    return new ProcessContext(
        new com.aipersimmon.ddd.processmanager.model.ProcessRef(
            new com.aipersimmon.ddd.processmanager.model.ProcessInstanceId("i-1"),
            new com.aipersimmon.ddd.processmanager.model.ProcessType("Ordering"),
            new com.aipersimmon.ddd.processmanager.model.ProcessBusinessKey("order-1")),
        com.aipersimmon.ddd.processmanager.model.ProcessRevision.initial(),
        com.aipersimmon.ddd.processmanager.model.DefinitionVersion.INITIAL,
        Optional.empty(),
        Optional.empty(),
        Instant.EPOCH,
        com.aipersimmon.ddd.cqrs.CommandContext.root(
            com.aipersimmon.ddd.tenancy.Tenants.of("acme"), "m-1"));
  }

  @Test
  void requiredFieldsAreValidated() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ProcessDecision<>(
                null, ProcessLifecycle.RUNNING, STEP, Optional.empty(), CODE, List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProcessDecision<>(new State("s"), null, STEP, Optional.empty(), CODE, List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ProcessDecision<>(
                new State("s"), ProcessLifecycle.RUNNING, null, Optional.empty(), CODE, List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ProcessDecision<>(
                new State("s"), ProcessLifecycle.RUNNING, STEP, null, CODE, List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ProcessDecision<>(
                new State("s"), ProcessLifecycle.RUNNING, STEP, Optional.empty(), null, List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ProcessDecision<>(
                new State("s"), ProcessLifecycle.RUNNING, STEP, Optional.empty(), CODE, null));
  }
}
