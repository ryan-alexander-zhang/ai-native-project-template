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
import com.aipersimmon.ddd.processmanager.effect.DispatchCommand;
import com.aipersimmon.ddd.processmanager.model.DefinitionVersion;
import com.aipersimmon.ddd.processmanager.model.ProcessBusinessKey;
import com.aipersimmon.ddd.processmanager.model.ProcessInstanceId;
import com.aipersimmon.ddd.processmanager.model.ProcessLifecycle;
import com.aipersimmon.ddd.processmanager.model.ProcessRef;
import com.aipersimmon.ddd.processmanager.model.ProcessRevision;
import com.aipersimmon.ddd.processmanager.model.ProcessStep;
import com.example.ordering.application.order.CancelOrder;
import com.example.ordering.application.order.ConfirmOrder;
import com.example.ordering.application.order.RequestPayment;
import com.example.ordering.application.order.RequestStockRelease;
import com.example.ordering.domain.order.CancellationReason;
import com.example.ordering.process.fulfilment.OrderFulfilmentState.Step;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the pure {@link OrderFulfilmentDefinition} transition table — the first tests in
 * the {@code ordering-process-jdbc} module (issue-00035). They drive {@code react} directly with a
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
  private final OrderFulfilmentDefinition definition = new OrderFulfilmentDefinition();

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

    RequestPayment command = assertInstanceOf(RequestPayment.class, onlyCommand(decision));
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
    assertInstanceOf(ConfirmOrder.class, onlyCommand(decision));
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
    CancelOrder cancel = assertInstanceOf(CancelOrder.class, onlyCommand(decision));
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
        assertInstanceOf(RequestStockRelease.class, onlyCommand(decision));
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
    CancelOrder cancel = assertInstanceOf(CancelOrder.class, onlyCommand(decision));
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
                        onlyCommand(
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
                    onlyCommand(
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
  void orderPlacedReachingReactIsRejected() {
    assertThrows(
        IllegalStateException.class,
        () ->
            definition.react(
                awaitingStock(),
                new OrderFulfilmentInput.OrderPlaced(ORDER),
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

  private static Command<?> onlyCommand(ProcessDecision<OrderFulfilmentState> decision) {
    assertEquals(1, decision.effects().size(), "expected exactly one effect");
    return assertInstanceOf(DispatchCommand.class, decision.effects().get(0)).command();
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
        CommandContext.root(causeMessageId));
  }
}
