package com.example.ordering.process.fulfilment;

import com.aipersimmon.ddd.processmanager.definition.ProcessContext;
import com.aipersimmon.ddd.processmanager.definition.ProcessDecision;
import com.aipersimmon.ddd.processmanager.definition.ProcessDefinition;
import com.aipersimmon.ddd.processmanager.definition.ProcessInput;
import com.aipersimmon.ddd.processmanager.effect.DispatchCommand;
import com.aipersimmon.ddd.processmanager.effect.ProcessEffect;
import com.aipersimmon.ddd.processmanager.exception.UnsupportedProcessInputException;
import com.aipersimmon.ddd.processmanager.model.DecisionCode;
import com.aipersimmon.ddd.processmanager.model.DefinitionVersion;
import com.aipersimmon.ddd.processmanager.model.ProcessLifecycle;
import com.aipersimmon.ddd.processmanager.model.ProcessOutcome;
import com.aipersimmon.ddd.processmanager.model.ProcessStep;
import com.aipersimmon.ddd.processmanager.model.ProcessType;
import com.aipersimmon.ddd.processmanager.model.StateSchemaVersion;
import com.example.ordering.application.order.CancelOrder;
import com.example.ordering.application.order.ConfirmOrder;
import com.example.ordering.application.order.RequestPayment;
import com.example.ordering.application.order.RequestStockRelease;
import com.example.ordering.domain.order.CancellationReason;
import com.example.ordering.domain.order.OrderId;
import com.example.ordering.domain.order.PaymentDeclineRef;
import com.example.ordering.domain.order.ReservationFailureRef;
import com.example.ordering.domain.order.StockReleaseRef;
import com.example.ordering.process.fulfilment.OrderFulfilmentState.Step;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The order-fulfilment coordination policy as a pure, deterministic {@link ProcessDefinition} — the
 * durable process-manager replacement for the old orchestration saga. Given the current state and
 * an input, it returns the next state, lifecycle, and the ordering commands to dispatch; the
 * durable runtime persists the transition and relays the commands at-least-once.
 *
 * <p>The flow, and the two properties that keep it honest:
 *
 * <pre>
 *   OrderReadyForFulfilment ─▶ AWAITING_STOCK
 *     StockReserved ─▶ RequestPayment ─▶ AWAITING_PAYMENT
 *       PaymentAuthorized ─▶ ConfirmOrder ─▶ AWAITING_ORDER_CONFIRMATION
 *         OrderConfirmed ─▶ COMPLETED (ORDER_CONFIRMED)
 *       PaymentDeclined  ─▶ COMPENSATING, RequestStockRelease ─▶ AWAITING_STOCK_RELEASE
 *         StockReleased  ─▶ CancelOrder(PaymentDeclinedAfterStockReleased) ─▶ AWAITING_ORDER_CANCELLATION
 *           OrderCancelled ─▶ COMPLETED (ORDER_CANCELLED)
 *     StockReservationFailed ─▶ COMPENSATING, CancelOrder(InventoryUnavailable) ─▶ AWAITING_ORDER_CANCELLATION
 * </pre>
 *
 * It reaches a terminal lifecycle only on {@code OrderConfirmed}/{@code OrderCancelled} — the
 * actual outcome — never when a confirm/cancel command is merely dispatched. And compensation is
 * ordered: a payment decline goes through stock release before cancellation, so the
 * evidence-bearing {@link CancellationReason.PaymentDeclinedAfterStockReleased} can only be built
 * once the stock the order held was actually released.
 *
 * <h2>Ordering by current step, not just by input type</h2>
 *
 * {@link #react} gates every fact on the {@linkplain OrderFulfilmentState#step() current step}, not
 * on the input type alone. For each {@code (step, input)} pair it does exactly one of:
 *
 * <ul>
 *   <li><b>advance / compensate / complete</b> — the step's expected fact drives the flow forward;
 *   <li><b>ignore</b> — an idempotent no-op (same lifecycle, same step, no effects) for a fact that
 *       is a duplicate of one already handled, or is out of order for this step. Because the
 *       runtime delivers at-least-once and treats a {@code react} throw as a poison message it
 *       retries forever, an out-of-order or stale fact must <em>not</em> throw — it is ignored.
 *       This closes the three misbehaviours a type-only switch had: {@code PaymentAuthorized} at
 *       {@code AWAITING_STOCK} no longer confirms an un-reserved order; {@code PaymentDeclined}
 *       before a reservation no longer releases a {@code null} handle; {@code StockReleased} before
 *       a decline no longer throws on a {@code null} decline code and wedges the queue;
 *   <li><b>reject</b> — a throw, reserved for {@link OrderFulfilmentInput.ReadyForFulfilment},
 *       which is a start-only input that structurally never reaches {@code react} (so the throw
 *       cannot poison a real redelivery) and only signals a wiring defect.
 * </ul>
 *
 * <p>Evidence refs carry the identity of the <em>causing envelope</em> ({@code
 * context.cause().messageId()}), not a business key: the reservation-failure, payment-decline, and
 * stock-release ids are each the id of the event that produced them, so they are distinct and
 * traceable rather than colliding on {@code orderId}/{@code reservationId}. The decline event's id
 * is remembered in the state when payment declines, so the eventual cancellation names the true
 * decline event even though the current cause is the stock-released event.
 */
@Component
public class OrderFulfilmentDefinition implements ProcessDefinition<OrderFulfilmentState> {

  public static final ProcessType PROCESS_TYPE = new ProcessType("ordering.fulfilment");

  @Override
  public ProcessType processType() {
    return PROCESS_TYPE;
  }

  @Override
  public DefinitionVersion definitionVersion() {
    return new DefinitionVersion("v1");
  }

  @Override
  public boolean activeForNewInstances() {
    return true;
  }

  @Override
  public StateSchemaVersion stateSchemaVersion() {
    return new StateSchemaVersion(1);
  }

  @Override
  public ProcessDecision<OrderFulfilmentState> start(ProcessInput input, ProcessContext context) {
    if (input instanceof OrderFulfilmentInput.ReadyForFulfilment ready) {
      OrderFulfilmentState state =
          new OrderFulfilmentState(ready.orderId(), Step.AWAITING_STOCK, null, null, null);
      return running(state, Step.AWAITING_STOCK, "ready-for-fulfilment");
    }
    throw new IllegalStateException("unexpected start input: " + input);
  }

  @Override
  public ProcessDecision<OrderFulfilmentState> react(
      OrderFulfilmentState state, ProcessInput input, ProcessContext context) {
    // Every business fact is an OrderFulfilmentInput. The only input that is not is the runtime's
    // MaxLifetimeExceeded backstop, which the runtime fires when instance.max-lifetime is
    // configured. This scaffold does not arm that backstop, so the input should never arrive; if a
    // user enables it without also handling it here, reject it cleanly — the runtime then suspends
    // the instance for operator attention — rather than crashing on a ClassCastException.
    if (!(input instanceof OrderFulfilmentInput in)) {
      throw new UnsupportedProcessInputException(
          "order-fulfilment does not handle the runtime input "
              + input.getClass().getSimpleName()
              + "; handle MaxLifetimeExceeded here to use the max-lifetime backstop");
    }
    return switch (state.step()) {
      case AWAITING_STOCK -> onAwaitingStock(state, in, context);
      case AWAITING_PAYMENT -> onAwaitingPayment(state, in, context);
      case AWAITING_STOCK_RELEASE -> onAwaitingStockRelease(state, in, context);
      case AWAITING_ORDER_CONFIRMATION -> onAwaitingOrderConfirmation(state, in, context);
      case AWAITING_ORDER_CANCELLATION -> onAwaitingOrderCancellation(state, in, context);
      // A react on a terminal step should not occur (the runtime no-ops terminal instances); if
      // one is redelivered, keep it idempotent rather than throwing.
      case CONFIRMED, CANCELLED -> ignore(state, in, context);
    };
  }

  /** Waiting for inventory: reserve advances to payment; failure compensates; all else is stale. */
  private ProcessDecision<OrderFulfilmentState> onAwaitingStock(
      OrderFulfilmentState state, OrderFulfilmentInput in, ProcessContext context) {
    if (in instanceof OrderFulfilmentInput.StockReserved reserved) {
      String orderId = state.orderId();
      String paymentOperationId = context.cause().messageId();
      return running(
          state.reserved(reserved.reservationId(), Step.AWAITING_PAYMENT),
          Step.AWAITING_PAYMENT,
          "stock-reserved",
          new DispatchCommand(new RequestPayment(orderId, paymentOperationId)));
    }
    if (in instanceof OrderFulfilmentInput.StockReservationFailed failed) {
      ReservationFailureRef failure =
          new ReservationFailureRef(
              context.cause().messageId(),
              new OrderId(state.orderId()),
              failed.code(),
              failed.reason());
      return compensating(
          state.withStep(Step.AWAITING_ORDER_CANCELLATION),
          Step.AWAITING_ORDER_CANCELLATION,
          "stock-reservation-failed",
          new DispatchCommand(
              new CancelOrder(
                  state.orderId(), new CancellationReason.InventoryUnavailable(failure))));
    }
    return ignore(state, in, context);
  }

  /** Stock reserved, waiting for payment: authorise advances to confirm; decline compensates. */
  private ProcessDecision<OrderFulfilmentState> onAwaitingPayment(
      OrderFulfilmentState state, OrderFulfilmentInput in, ProcessContext context) {
    if (in instanceof OrderFulfilmentInput.PaymentAuthorized) {
      return running(
          state.withStep(Step.AWAITING_ORDER_CONFIRMATION),
          Step.AWAITING_ORDER_CONFIRMATION,
          "payment-authorized",
          new DispatchCommand(new ConfirmOrder(state.orderId())));
    }
    if (in instanceof OrderFulfilmentInput.PaymentDeclined declined) {
      return compensating(
          state.declined(declined.code(), context.cause().messageId(), Step.AWAITING_STOCK_RELEASE),
          Step.AWAITING_STOCK_RELEASE,
          "payment-declined",
          new DispatchCommand(new RequestStockRelease(state.orderId(), state.reservationId())));
    }
    return ignore(state, in, context);
  }

  /** Compensating, waiting for the reserved stock to be released before cancelling. */
  private ProcessDecision<OrderFulfilmentState> onAwaitingStockRelease(
      OrderFulfilmentState state, OrderFulfilmentInput in, ProcessContext context) {
    if (in instanceof OrderFulfilmentInput.StockReleased) {
      OrderId id = new OrderId(state.orderId());
      // Two distinct evidence ids: the decline ref keeps the remembered decline-event id; the
      // release ref takes the current stock-released event's id.
      CancellationReason reason =
          new CancellationReason.PaymentDeclinedAfterStockReleased(
              new PaymentDeclineRef(
                  state.paymentDeclineEvidenceId(), id, state.paymentDeclineCode()),
              new StockReleaseRef(context.cause().messageId(), id));
      return compensating(
          state.withStep(Step.AWAITING_ORDER_CANCELLATION),
          Step.AWAITING_ORDER_CANCELLATION,
          "stock-released",
          new DispatchCommand(new CancelOrder(state.orderId(), reason)));
    }
    return ignore(state, in, context);
  }

  /** Success branch, waiting for the order-confirmed fact to reach the terminal outcome. */
  private ProcessDecision<OrderFulfilmentState> onAwaitingOrderConfirmation(
      OrderFulfilmentState state, OrderFulfilmentInput in, ProcessContext context) {
    if (in instanceof OrderFulfilmentInput.OrderConfirmed) {
      return completed(
          state.withStep(Step.CONFIRMED), Step.CONFIRMED, "order-confirmed", "ORDER_CONFIRMED");
    }
    return ignore(state, in, context);
  }

  /** Compensation branch, waiting for the order-cancelled fact to reach the terminal outcome. */
  private ProcessDecision<OrderFulfilmentState> onAwaitingOrderCancellation(
      OrderFulfilmentState state, OrderFulfilmentInput in, ProcessContext context) {
    if (in instanceof OrderFulfilmentInput.OrderCancelled) {
      return completed(
          state.withStep(Step.CANCELLED), Step.CANCELLED, "order-cancelled", "ORDER_CANCELLED");
    }
    return ignore(state, in, context);
  }

  private static ProcessDecision<OrderFulfilmentState> running(
      OrderFulfilmentState state, Step step, String code, ProcessEffect... effects) {
    return decision(state, ProcessLifecycle.RUNNING, step, Optional.empty(), code, effects);
  }

  private static ProcessDecision<OrderFulfilmentState> compensating(
      OrderFulfilmentState state, Step step, String code, ProcessEffect... effects) {
    return decision(state, ProcessLifecycle.COMPENSATING, step, Optional.empty(), code, effects);
  }

  private static ProcessDecision<OrderFulfilmentState> completed(
      OrderFulfilmentState state, Step step, String code, String outcome) {
    return decision(
        state, ProcessLifecycle.COMPLETED, step, Optional.of(new ProcessOutcome(outcome)), code);
  }

  /**
   * The no-op arm of the transition table: keep the current lifecycle and step and emit no effects,
   * so a duplicate or out-of-order fact is absorbed idempotently instead of driving a wrong
   * transition or throwing. {@link OrderFulfilmentInput.ReadyForFulfilment} is the one input that
   * is rejected here: it is start-only and never reaches {@code react} in normal operation.
   */
  private static ProcessDecision<OrderFulfilmentState> ignore(
      OrderFulfilmentState state, OrderFulfilmentInput in, ProcessContext context) {
    if (in instanceof OrderFulfilmentInput.ReadyForFulfilment) {
      throw new IllegalStateException(
          "ReadyForFulfilment is a start-only input and must not reach react for order "
              + state.orderId());
    }
    ProcessLifecycle current =
        context
            .currentLifecycle()
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "react requires a current lifecycle for order " + state.orderId()));
    String code = "ignored:" + state.step() + ":" + in.getClass().getSimpleName();
    return decision(state, current, state.step(), Optional.empty(), code);
  }

  private static ProcessDecision<OrderFulfilmentState> decision(
      OrderFulfilmentState state,
      ProcessLifecycle lifecycle,
      Step step,
      Optional<ProcessOutcome> outcome,
      String code,
      ProcessEffect... effects) {
    return new ProcessDecision<>(
        state,
        lifecycle,
        new ProcessStep(step.name()),
        outcome,
        new DecisionCode(code),
        List.of(effects));
  }
}
