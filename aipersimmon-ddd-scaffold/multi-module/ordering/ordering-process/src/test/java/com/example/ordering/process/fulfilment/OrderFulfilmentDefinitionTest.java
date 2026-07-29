package com.example.ordering.process.fulfilment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.cqrs.Command;
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
import com.example.ordering.application.order.BeginFulfilment;
import com.example.ordering.application.order.CancelOrder;
import com.example.ordering.application.order.ConfirmOrder;
import com.example.ordering.application.order.RequestPayment;
import com.example.ordering.application.order.RequestStockRelease;
import com.example.ordering.domain.order.CancellationReason;
import com.example.ordering.process.fulfilment.OrderFulfilmentState.Step;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the pure {@link OrderFulfilmentDefinition} transition table — the first tests in
 * the {@code ordering-process} module (issue-00035). They drive {@code react} directly with a
 * hand-built {@link ProcessContext}, so no runtime, database, or Spring context is involved.
 *
 * <p>They cover the whole {@code (step, input)} matrix: the happy path, both compensation branches,
 * and — the point of the fix — the out-of-order and duplicate facts that a type-only switch
 * mishandled. They also pin the evidence-id source (issue-00042): each ref's id is the causing
 * envelope's {@code messageId}, and the two refs a cancellation carries are distinct; and the
 * stable {@code paymentOperationId} the process manager derives (issue-00041).
 */
class OrderFulfilmentDefinitionTest {

  private static final String ORDER = "order-1";
  private static final Duration PAYMENT_TIMEOUT = Duration.ofMinutes(2);
  private static final Duration STOCK_TIMEOUT = Duration.ofMinutes(1);
  private static final Duration STOCK_RELEASE_TIMEOUT = Duration.ofSeconds(45);
  private final OrderFulfilmentDefinition definition =
      new OrderFulfilmentDefinition(PAYMENT_TIMEOUT, STOCK_TIMEOUT, STOCK_RELEASE_TIMEOUT);

  // ---------- happy path ----------

  @Test
  void stockReservedAdvancesToPaymentAndDerivesStableOperationIdFromTheCause() {
    OrderFulfilmentState state = awaitingStock();
    ProcessDecision<OrderFulfilmentState> decision =
        definition.react(
            state,
            new OrderFulfilmentInput.StockReserved(ORDER, "res-1"),
            context("msg-reserved", ProcessLifecycle.RUNNING, Step.AWAITING_STOCK));

    assertEquals(ProcessLifecycle.RUNNING, decision.lifecycle());
    assertEquals(Step.AWAITING_PAYMENT.name(), decision.step().value());
    assertEquals("res-1", decision.state().reservationId());

    // Two commands, and the pairing is the point: the reservation now exists, so this is the moment
    // the order is genuinely under fulfilment and the moment payment can be asked for. Ordering's
    // own state used to be advanced at placement, before anything had been reserved (issue-00070).
    assertEquals(ORDER, dispatchedCommandOfType(decision, BeginFulfilment.class).orderId());
    RequestPayment command = dispatchedCommandOfType(decision, RequestPayment.class);
    assertEquals(ORDER, command.orderId());
    // The business idempotency key is the stable identity of the triggering fact (the cause), so a
    // redelivery of the same StockReserved yields the same paymentOperationId (issue-00041).
    assertEquals("msg-reserved", command.paymentOperationId());
  }

  @Test
  void paymentAuthorizedAdvancesToOrderConfirmation() {
    OrderFulfilmentState state = awaitingPayment();
    ProcessDecision<OrderFulfilmentState> decision =
        definition.react(
            state,
            new OrderFulfilmentInput.PaymentAuthorized(ORDER),
            context("msg-auth", ProcessLifecycle.RUNNING, Step.AWAITING_PAYMENT));

    assertEquals(ProcessLifecycle.RUNNING, decision.lifecycle());
    assertEquals(Step.AWAITING_ORDER_CONFIRMATION.name(), decision.step().value());
    assertInstanceOf(ConfirmOrder.class, dispatchedCommand(decision));
  }

  @Test
  void orderConfirmedCompletesWithTheConfirmedOutcomeAndNoEffects() {
    OrderFulfilmentState state = awaitingPayment().withStep(Step.AWAITING_ORDER_CONFIRMATION);
    ProcessDecision<OrderFulfilmentState> decision =
        definition.react(
            state,
            new OrderFulfilmentInput.OrderConfirmed(ORDER),
            context("msg-confirmed", ProcessLifecycle.RUNNING, Step.AWAITING_ORDER_CONFIRMATION));

    assertEquals(ProcessLifecycle.COMPLETED, decision.lifecycle());
    assertEquals("ORDER_CONFIRMED", decision.outcome().orElseThrow().value());
    assertEquals(Step.CONFIRMED.name(), decision.step().value());
    assertTrue(decision.effects().isEmpty());
  }

  // ---------- the payment deadline ----------

  @Test
  void reservingStockArmsThePaymentDeadlineAlongsideTheRequest() {
    ProcessDecision<OrderFulfilmentState> decision =
        definition.react(
            awaitingStock(),
            new OrderFulfilmentInput.StockReserved(ORDER, "res-1"),
            context("msg-reserved", ProcessLifecycle.RUNNING, Step.AWAITING_STOCK));

    // Asking and arming are one decision: the flow can never be left waiting on an answer that
    // nobody is obliged to send.
    ScheduleDeadline armed = onlyEffectOfType(decision, ScheduleDeadline.class);
    assertEquals(OrderFulfilmentDefinition.PAYMENT_DEADLINE, armed.name());
    // Due time comes from the runtime-supplied now, so the decision is repeatable on redelivery.
    assertEquals(Instant.EPOCH.plus(PAYMENT_TIMEOUT), armed.dueAt());
    assertEquals(new OrderFulfilmentInput.PaymentTimedOut(ORDER), armed.input());
  }

  @Test
  void anAnsweredPaymentCancelsTheDeadline() {
    ProcessDecision<OrderFulfilmentState> authorized =
        definition.react(
            awaitingPayment(),
            new OrderFulfilmentInput.PaymentAuthorized(ORDER),
            context("msg-auth", ProcessLifecycle.RUNNING, Step.AWAITING_PAYMENT));
    ProcessDecision<OrderFulfilmentState> declined =
        definition.react(
            awaitingPayment(),
            new OrderFulfilmentInput.PaymentDeclined(ORDER, "DECLINED", "over ceiling"),
            context("msg-declined", ProcessLifecycle.RUNNING, Step.AWAITING_PAYMENT));

    assertEquals(
        OrderFulfilmentDefinition.PAYMENT_DEADLINE,
        onlyEffectOfType(authorized, CancelDeadline.class).name());
    assertEquals(
        OrderFulfilmentDefinition.PAYMENT_DEADLINE,
        onlyEffectOfType(declined, CancelDeadline.class).name());
  }

  @Test
  void aTimedOutPaymentCompensatesExactlyAsADeclineDoes() {
    ProcessDecision<OrderFulfilmentState> decision =
        definition.react(
            awaitingPayment(),
            new OrderFulfilmentInput.PaymentTimedOut(ORDER),
            context("msg-timeout", ProcessLifecycle.RUNNING, Step.AWAITING_PAYMENT));

    assertEquals(ProcessLifecycle.COMPENSATING, decision.lifecycle());
    assertEquals(Step.AWAITING_STOCK_RELEASE.name(), decision.step().value());
    RequestStockRelease release =
        assertInstanceOf(RequestStockRelease.class, dispatchedCommand(decision));
    assertEquals("res-1", release.reservationId(), "the same handle inventory issued");
    // What distinguishes it afterwards is the recorded code and the evidence, not the path.
    assertEquals("PAYMENT_TIMEOUT", decision.state().paymentDeclineCode());
    assertEquals("msg-timeout", decision.state().paymentDeclineEvidenceId());
    // Nothing to cancel: this decision is the deadline firing.
    assertNoEffectOfType(decision, CancelDeadline.class);
  }

  @Test
  void aTimerThatFiresAfterPaymentAlreadyAnsweredIsIgnored() {
    OrderFulfilmentState state = awaitingPayment().withStep(Step.AWAITING_ORDER_CONFIRMATION);

    ProcessDecision<OrderFulfilmentState> decision =
        definition.react(
            state,
            new OrderFulfilmentInput.PaymentTimedOut(ORDER),
            context(
                "msg-late-timeout", ProcessLifecycle.RUNNING, Step.AWAITING_ORDER_CONFIRMATION));

    // The generation guard should already have stopped it; the step table refuses it as well,
    // because an ignored late timer must not undo a confirmation.
    assertEquals(Step.AWAITING_ORDER_CONFIRMATION.name(), decision.step().value());
    assertTrue(decision.effects().isEmpty());
  }

  // ---------- the stock deadlines (issue-00068) ----------

  @Test
  void startingTheFlowArmsTheStockDeadline() {
    ProcessDecision<OrderFulfilmentState> decision =
        definition.start(
            new OrderFulfilmentInput.ReadyForFulfilment(ORDER),
            context("msg-ready", ProcessLifecycle.RUNNING, Step.AWAITING_STOCK));

    // A wait must not be able to begin without a way out. Inventory answers only when it judges a
    // *business* failure; a technical one throws and publishes nothing, and that silence used to
    // park the order in FULFILMENT_IN_PROGRESS with no timer, no alert and no operator route out.
    ScheduleDeadline armed = onlyEffectOfType(decision, ScheduleDeadline.class);
    assertEquals(OrderFulfilmentDefinition.STOCK_DEADLINE, armed.name());
    assertEquals(Instant.EPOCH.plus(STOCK_TIMEOUT), armed.dueAt());
    assertEquals(new OrderFulfilmentInput.StockReservationTimedOut(ORDER), armed.input());
  }

  @Test
  void answeringTheReservationCancelsTheStockDeadline() {
    ProcessDecision<OrderFulfilmentState> reserved =
        definition.react(
            awaitingStock(),
            new OrderFulfilmentInput.StockReserved(ORDER, "res-1"),
            context("msg-reserved", ProcessLifecycle.RUNNING, Step.AWAITING_STOCK));
    ProcessDecision<OrderFulfilmentState> failed =
        definition.react(
            awaitingStock(),
            new OrderFulfilmentInput.StockReservationFailed(ORDER, "NO_STOCK", "sold out"),
            context("msg-failed", ProcessLifecycle.RUNNING, Step.AWAITING_STOCK));

    assertEquals(
        OrderFulfilmentDefinition.STOCK_DEADLINE,
        onlyEffectOfType(reserved, CancelDeadline.class).name());
    assertEquals(
        OrderFulfilmentDefinition.STOCK_DEADLINE,
        onlyEffectOfType(failed, CancelDeadline.class).name());
  }

  @Test
  void aTimedOutReservationCancelsTheOrderExactlyAsARefusalDoes() {
    ProcessDecision<OrderFulfilmentState> decision =
        definition.react(
            awaitingStock(),
            new OrderFulfilmentInput.StockReservationTimedOut(ORDER),
            context("msg-stock-timeout", ProcessLifecycle.RUNNING, Step.AWAITING_STOCK));

    assertEquals(ProcessLifecycle.COMPENSATING, decision.lifecycle());
    assertEquals(Step.AWAITING_ORDER_CANCELLATION.name(), decision.step().value());

    // Nothing was reserved, so there is nothing to release: straight to cancellation, the same
    // branch a refusal takes. A new way to fail did not need a new way to recover.
    CancelOrder cancel = assertInstanceOf(CancelOrder.class, dispatchedCommand(decision));
    CancellationReason.InventoryUnavailable reason =
        assertInstanceOf(CancellationReason.InventoryUnavailable.class, cancel.reason());
    assertEquals("STOCK_TIMEOUT", reason.failure().reasonCode(), "silence, not a refusal");
    assertEquals("msg-stock-timeout", reason.failure().failureId(), "evidence is the firing");
    // Nothing to cancel: this decision is the deadline firing.
    assertNoEffectOfType(decision, CancelDeadline.class);
  }

  @Test
  void aTimedOutReleaseAsksAgainInsteadOfCancellingWithoutEvidence() {
    ProcessDecision<OrderFulfilmentState> decision =
        definition.react(
            awaitingStockRelease("msg-declined"),
            new OrderFulfilmentInput.StockReleaseTimedOut(ORDER),
            context(
                "msg-release-timeout", ProcessLifecycle.COMPENSATING, Step.AWAITING_STOCK_RELEASE));

    // The one timeout that must not end its wait. Cancelling from here needs a StockReleaseRef,
    // and a timeout is the absence of one — giving up would mean recording that stock came back
    // when it has not. So the flow stays put and re-sends the request.
    assertEquals(ProcessLifecycle.COMPENSATING, decision.lifecycle());
    assertEquals(Step.AWAITING_STOCK_RELEASE.name(), decision.step().value());
    RequestStockRelease again =
        assertInstanceOf(RequestStockRelease.class, dispatchedCommand(decision));
    assertEquals("res-1", again.reservationId(), "the same handle, asked for again");

    ScheduleDeadline rearmed = onlyEffectOfType(decision, ScheduleDeadline.class);
    assertEquals(OrderFulfilmentDefinition.STOCK_RELEASE_DEADLINE, rearmed.name());
    assertEquals(Instant.EPOCH.plus(STOCK_RELEASE_TIMEOUT), rearmed.dueAt());
  }

  @Test
  void aCompletedReleaseCancelsTheReleaseDeadline() {
    ProcessDecision<OrderFulfilmentState> decision =
        definition.react(
            awaitingStockRelease("msg-declined"),
            new OrderFulfilmentInput.StockReleased(ORDER, "res-1"),
            context("msg-released", ProcessLifecycle.COMPENSATING, Step.AWAITING_STOCK_RELEASE));

    assertEquals(
        OrderFulfilmentDefinition.STOCK_RELEASE_DEADLINE,
        onlyEffectOfType(decision, CancelDeadline.class).name());
  }

  @Test
  void bothRoutesIntoStockReleaseArmItsDeadline() {
    ProcessDecision<OrderFulfilmentState> declined =
        definition.react(
            awaitingPayment(),
            new OrderFulfilmentInput.PaymentDeclined(ORDER, "DECLINED", "over ceiling"),
            context("msg-declined", ProcessLifecycle.RUNNING, Step.AWAITING_PAYMENT));
    ProcessDecision<OrderFulfilmentState> timedOut =
        definition.react(
            awaitingPayment(),
            new OrderFulfilmentInput.PaymentTimedOut(ORDER),
            context("msg-timeout", ProcessLifecycle.RUNNING, Step.AWAITING_PAYMENT));

    assertEquals(
        OrderFulfilmentDefinition.STOCK_RELEASE_DEADLINE,
        onlyEffectOfType(declined, ScheduleDeadline.class).name());
    assertEquals(
        OrderFulfilmentDefinition.STOCK_RELEASE_DEADLINE,
        onlyEffectOfType(timedOut, ScheduleDeadline.class).name());
  }

  // ---------- compensation branches + evidence identity (issue-00042) ----------

  @Test
  void reservationFailedCompensatesWithFailureEvidenceIdFromTheCause() {
    ProcessDecision<OrderFulfilmentState> decision =
        definition.react(
            awaitingStock(),
            new OrderFulfilmentInput.StockReservationFailed(ORDER, "OUT_OF_STOCK", "no stock"),
            context("msg-failed", ProcessLifecycle.RUNNING, Step.AWAITING_STOCK));

    assertEquals(ProcessLifecycle.COMPENSATING, decision.lifecycle());
    assertEquals(Step.AWAITING_ORDER_CANCELLATION.name(), decision.step().value());
    CancelOrder cancel = assertInstanceOf(CancelOrder.class, dispatchedCommand(decision));
    CancellationReason.InventoryUnavailable reason =
        assertInstanceOf(CancellationReason.InventoryUnavailable.class, cancel.reason());
    // Evidence id is the causing envelope's messageId, not orderId (issue-00042).
    assertEquals("msg-failed", reason.failure().failureId());
  }

  @Test
  void paymentDeclinedCompensatesRequestingReleaseOfTheReservedHandle() {
    ProcessDecision<OrderFulfilmentState> decision =
        definition.react(
            awaitingPayment(),
            new OrderFulfilmentInput.PaymentDeclined(ORDER, "DECLINED", "over ceiling"),
            context("msg-declined", ProcessLifecycle.RUNNING, Step.AWAITING_PAYMENT));

    assertEquals(ProcessLifecycle.COMPENSATING, decision.lifecycle());
    assertEquals(Step.AWAITING_STOCK_RELEASE.name(), decision.step().value());
    RequestStockRelease release =
        assertInstanceOf(RequestStockRelease.class, dispatchedCommand(decision));
    assertEquals("res-1", release.reservationId());
    // The decline code and the decline event's identity are remembered for the later cancellation.
    assertEquals("DECLINED", decision.state().paymentDeclineCode());
    assertEquals("msg-declined", decision.state().paymentDeclineEvidenceId());
  }

  @Test
  void stockReleasedCancelsWithTwoDistinctEvidenceIds() {
    OrderFulfilmentState state = awaitingStockRelease("msg-declined");
    ProcessDecision<OrderFulfilmentState> decision =
        definition.react(
            state,
            new OrderFulfilmentInput.StockReleased(ORDER, "res-1"),
            context("msg-released", ProcessLifecycle.COMPENSATING, Step.AWAITING_STOCK_RELEASE));

    assertEquals(ProcessLifecycle.COMPENSATING, decision.lifecycle());
    assertEquals(Step.AWAITING_ORDER_CANCELLATION.name(), decision.step().value());
    CancelOrder cancel = assertInstanceOf(CancelOrder.class, dispatchedCommand(decision));
    CancellationReason.PaymentDeclinedAfterStockReleased reason =
        assertInstanceOf(
            CancellationReason.PaymentDeclinedAfterStockReleased.class, cancel.reason());
    // The decline ref keeps the decline event's id; the release ref takes the release event's id —
    // distinct identities, not the same business key twice (issue-00042).
    assertEquals("msg-declined", reason.paymentDecline().declineId());
    assertEquals("msg-released", reason.stockRelease().releaseId());
    assertNotEquals(reason.paymentDecline().declineId(), reason.stockRelease().releaseId());
  }

  @Test
  void orderCancelledCompletesWithTheCancelledOutcome() {
    OrderFulfilmentState state =
        awaitingStockRelease("msg-declined").withStep(Step.AWAITING_ORDER_CANCELLATION);
    ProcessDecision<OrderFulfilmentState> decision =
        definition.react(
            state,
            new OrderFulfilmentInput.OrderCancelled(ORDER),
            context(
                "msg-cancelled", ProcessLifecycle.COMPENSATING, Step.AWAITING_ORDER_CANCELLATION));

    assertEquals(ProcessLifecycle.COMPLETED, decision.lifecycle());
    assertEquals("ORDER_CANCELLED", decision.outcome().orElseThrow().value());
    assertEquals(Step.CANCELLED.name(), decision.step().value());
  }

  @Test
  void theThreeEvidenceIdsAcrossAFlowAreAllDistinct() {
    String failureId =
        ((CancellationReason.InventoryUnavailable)
                ((CancelOrder)
                        dispatchedCommand(
                            definition.react(
                                awaitingStock(),
                                new OrderFulfilmentInput.StockReservationFailed(ORDER, "C", "d"),
                                context(
                                    "id-failed", ProcessLifecycle.RUNNING, Step.AWAITING_STOCK))))
                    .reason())
            .failure()
            .failureId();
    CancellationReason.PaymentDeclinedAfterStockReleased decline =
        (CancellationReason.PaymentDeclinedAfterStockReleased)
            ((CancelOrder)
                    dispatchedCommand(
                        definition.react(
                            awaitingStockRelease("id-declined"),
                            new OrderFulfilmentInput.StockReleased(ORDER, "res-1"),
                            context(
                                "id-released",
                                ProcessLifecycle.COMPENSATING,
                                Step.AWAITING_STOCK_RELEASE))))
                .reason();

    Set<String> ids =
        new HashSet<>(
            Set.of(
                failureId,
                decline.paymentDecline().declineId(),
                decline.stockRelease().releaseId()));
    assertEquals(3, ids.size(), "failure, decline, and release evidence ids must be distinct");
  }

  // ---------- out-of-order facts must be ignored, not mis-handled (issue-00035) ----------

  @Nested
  class OutOfOrderFactsAreIgnored {

    @Test
    void paymentAuthorizedBeforeReservationDoesNotConfirmTheOrder() {
      ProcessDecision<OrderFulfilmentState> decision =
          definition.react(
              awaitingStock(),
              new OrderFulfilmentInput.PaymentAuthorized(ORDER),
              context("msg", ProcessLifecycle.RUNNING, Step.AWAITING_STOCK));
      assertIgnored(decision, ProcessLifecycle.RUNNING, Step.AWAITING_STOCK);
    }

    @Test
    void paymentDeclinedBeforeReservationDoesNotReleaseANullHandle() {
      ProcessDecision<OrderFulfilmentState> decision =
          definition.react(
              awaitingStock(),
              new OrderFulfilmentInput.PaymentDeclined(ORDER, "D", "r"),
              context("msg", ProcessLifecycle.RUNNING, Step.AWAITING_STOCK));
      assertIgnored(decision, ProcessLifecycle.RUNNING, Step.AWAITING_STOCK);
    }

    @Test
    void stockReleasedBeforeADeclineIsIgnoredAndDoesNotThrow() {
      // Previously this built a PaymentDeclineRef from a null decline code, throwing a
      // DomainException the runtime could not retry past — a poison message. Now it is ignored.
      ProcessDecision<OrderFulfilmentState> decision =
          assertDoesNotThrow(
              () ->
                  definition.react(
                      awaitingStock(),
                      new OrderFulfilmentInput.StockReleased(ORDER, "res-1"),
                      context("msg", ProcessLifecycle.RUNNING, Step.AWAITING_STOCK)));
      assertIgnored(decision, ProcessLifecycle.RUNNING, Step.AWAITING_STOCK);
    }

    @Test
    void duplicateStockReservedWhileAwaitingPaymentIsIgnored() {
      ProcessDecision<OrderFulfilmentState> decision =
          definition.react(
              awaitingPayment(),
              new OrderFulfilmentInput.StockReserved(ORDER, "res-1"),
              context("msg", ProcessLifecycle.RUNNING, Step.AWAITING_PAYMENT));
      assertIgnored(decision, ProcessLifecycle.RUNNING, Step.AWAITING_PAYMENT);
    }

    @Test
    void duplicatePaymentDeclinedWhileCompensatingKeepsTheCompensatingLifecycle() {
      ProcessDecision<OrderFulfilmentState> decision =
          definition.react(
              awaitingStockRelease("msg-declined"),
              new OrderFulfilmentInput.PaymentDeclined(ORDER, "D", "r"),
              context("msg", ProcessLifecycle.COMPENSATING, Step.AWAITING_STOCK_RELEASE));
      assertIgnored(decision, ProcessLifecycle.COMPENSATING, Step.AWAITING_STOCK_RELEASE);
    }
  }

  @Test
  void readyForFulfilmentReachingReactIsRejected() {
    assertThrows(
        IllegalStateException.class,
        () ->
            definition.react(
                awaitingStock(),
                new OrderFulfilmentInput.ReadyForFulfilment(ORDER),
                context("msg", ProcessLifecycle.RUNNING, Step.AWAITING_STOCK)));
  }

  // ---------- helpers ----------

  private static void assertIgnored(
      ProcessDecision<OrderFulfilmentState> decision, ProcessLifecycle lifecycle, Step step) {
    assertEquals(lifecycle, decision.lifecycle(), "ignore keeps the current lifecycle");
    assertEquals(step.name(), decision.step().value(), "ignore keeps the current step");
    assertTrue(decision.effects().isEmpty(), "ignore emits no effects");
    assertTrue(decision.outcome().isEmpty(), "ignore is not terminal");
    assertTrue(decision.decisionCode().value().startsWith("ignored:"), "ignore is labelled");
  }

  /**
   * The one command a decision dispatches. A decision may carry other effects alongside it — the
   * payment step arms and cancels a deadline — so this selects the dispatch rather than assuming it
   * is the only effect, while still insisting there is exactly one command.
   */
  private static Command<?> dispatchedCommand(ProcessDecision<OrderFulfilmentState> decision) {
    List<DispatchCommand> dispatches =
        decision.effects().stream()
            .filter(DispatchCommand.class::isInstance)
            .map(DispatchCommand.class::cast)
            .toList();
    assertEquals(1, dispatches.size(), "expected exactly one dispatched command");
    return dispatches.get(0).command();
  }

  /** The one dispatched command of the given type; a decision may dispatch several. */
  private static <T> T dispatchedCommandOfType(
      ProcessDecision<OrderFulfilmentState> decision, Class<T> type) {
    List<T> matching =
        decision.effects().stream()
            .filter(DispatchCommand.class::isInstance)
            .map(effect -> ((DispatchCommand) effect).command())
            .filter(type::isInstance)
            .map(type::cast)
            .toList();
    assertEquals(1, matching.size(), "expected exactly one dispatched " + type.getSimpleName());
    return matching.get(0);
  }

  private static <T> T onlyEffectOfType(
      ProcessDecision<OrderFulfilmentState> decision, Class<T> type) {
    List<T> matching =
        decision.effects().stream().filter(type::isInstance).map(type::cast).toList();
    assertEquals(1, matching.size(), "expected exactly one " + type.getSimpleName());
    return matching.get(0);
  }

  private static void assertNoEffectOfType(
      ProcessDecision<OrderFulfilmentState> decision, Class<?> type) {
    assertTrue(
        decision.effects().stream().noneMatch(type::isInstance),
        "expected no " + type.getSimpleName());
  }

  private static OrderFulfilmentState awaitingStock() {
    return new OrderFulfilmentState(ORDER, Step.AWAITING_STOCK, null, null, null);
  }

  private static OrderFulfilmentState awaitingPayment() {
    return new OrderFulfilmentState(ORDER, Step.AWAITING_PAYMENT, "res-1", null, null);
  }

  private static OrderFulfilmentState awaitingStockRelease(String declineEvidenceId) {
    return new OrderFulfilmentState(
        ORDER, Step.AWAITING_STOCK_RELEASE, "res-1", "DECLINED", declineEvidenceId);
  }

  private static ProcessContext context(
      String causeMessageId, ProcessLifecycle lifecycle, Step step) {
    ProcessRef ref =
        new ProcessRef(
            new ProcessInstanceId("inst-1"),
            OrderFulfilmentDefinition.PROCESS_TYPE,
            new ProcessBusinessKey(ORDER));
    return new ProcessContext(
        ref,
        ProcessRevision.initial(),
        new DefinitionVersion("v1"),
        java.util.Optional.of(lifecycle),
        java.util.Optional.of(new ProcessStep(step.name())),
        Instant.EPOCH,
        CommandContext.root(Tenants.ROOT.value(), causeMessageId));
  }
}
