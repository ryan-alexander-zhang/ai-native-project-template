package com.example.ordering.process.fulfilment;

import com.aipersimmon.ddd.processmanager.definition.ProcessContext;
import com.aipersimmon.ddd.processmanager.definition.ProcessDecision;
import com.aipersimmon.ddd.processmanager.definition.ProcessDefinition;
import com.aipersimmon.ddd.processmanager.definition.ProcessInput;
import com.aipersimmon.ddd.processmanager.effect.CancelDeadline;
import com.aipersimmon.ddd.processmanager.effect.DispatchCommand;
import com.aipersimmon.ddd.processmanager.effect.ProcessEffect;
import com.aipersimmon.ddd.processmanager.effect.ScheduleDeadline;
import com.aipersimmon.ddd.processmanager.exception.UnsupportedProcessInputException;
import com.aipersimmon.ddd.processmanager.model.DeadlineName;
import com.aipersimmon.ddd.processmanager.model.DecisionCode;
import com.aipersimmon.ddd.processmanager.model.ProcessLifecycle;
import com.aipersimmon.ddd.processmanager.model.ProcessOutcome;
import com.aipersimmon.ddd.processmanager.model.ProcessStep;
import com.aipersimmon.ddd.processmanager.model.ProcessType;
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
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The order-fulfilment coordination policy as a pure, deterministic {@link ProcessDefinition}.
 * Given the current state and an input, it returns the next state, lifecycle, and the ordering
 * commands to dispatch; the durable runtime persists the transition and relays the commands
 * at-least-once. Deciding and acting are separate on purpose: this class can be unit-tested with no
 * database and no clock.
 *
 * <p>The flow, and the two properties that keep it honest:
 *
 * <pre>
 *   OrderReadyForFulfilment ─▶ AWAITING_STOCK
 *     StockReserved ─▶ RequestPayment + arm PAYMENT deadline ─▶ AWAITING_PAYMENT
 *       PaymentAuthorized ─▶ ConfirmOrder, cancel deadline ─▶ AWAITING_ORDER_CONFIRMATION
 *         OrderConfirmed ─▶ COMPLETED (ORDER_CONFIRMED)
 *       PaymentDeclined  ─▶ COMPENSATING, RequestStockRelease, cancel deadline ─▶ AWAITING_STOCK_RELEASE
 *       PaymentTimedOut  ─▶ COMPENSATING, RequestStockRelease ─▶ AWAITING_STOCK_RELEASE
 *         StockReleased  ─▶ CancelOrder(PaymentDeclinedAfterStockReleased) ─▶ AWAITING_ORDER_CANCELLATION
 *           OrderCancelled ─▶ COMPLETED (ORDER_CANCELLED)
 *     StockReservationFailed ─▶ COMPENSATING, CancelOrder(InventoryUnavailable) ─▶ AWAITING_ORDER_CANCELLATION
 * </pre>
 *
 * <p><strong>A flow that waits must be able to stop waiting.</strong> Payment is the only step
 * whose answer comes from outside and may simply never arrive, so it is the only step with a timer.
 * The timeout takes the decline's compensation path unchanged — release the stock, then cancel —
 * because the customer's position is the same however the payment failed to happen; only the
 * recorded code differs. Without it, an order whose payment context went quiet holds its stock
 * forever, and the reservation is invisible to everyone except a stock count that will not add up.
 *
 * <p>It reaches a terminal lifecycle only on {@code OrderConfirmed}/{@code OrderCancelled} — the
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

  /**
   * The timer armed while payment is outstanding. A name rather than an id: rescheduling the same
   * name supersedes the previous generation, and cancelling it cancels only the current one, so a
   * timer that fires just as the answer arrives cannot resurrect a settled flow.
   */
  static final DeadlineName PAYMENT_DEADLINE = new DeadlineName("PAYMENT");

  /** Recorded as the decline code when the timer, not the payment context, ended the wait. */
  static final String PAYMENT_TIMEOUT_CODE = "PAYMENT_TIMEOUT";

  /**
   * How long payment may take before the flow gives up on it. Held as a field rather than read from
   * a clock or a property inside {@link #react}, so the decision stays a function of its arguments.
   */
  private final Duration paymentTimeout;

  public OrderFulfilmentDefinition(
      @Value("${ordering.fulfilment.payment-timeout:PT2M}") Duration paymentTimeout) {
    this.paymentTimeout = paymentTimeout;
  }

  @Override
  public ProcessType processType() {
    return PROCESS_TYPE;
  }

  // definitionVersion / activeForNewInstances / stateSchemaVersion are left at their defaults
  // (v1, active, schema 1) — this flow has one version. Overriding them only becomes necessary
  // when a second version has to run alongside this one; until then the values carry no
  // information, and the registry refuses to start if two versions ever collide.

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
      // Asking for payment and arming the timer are one decision, so the flow can never end up
      // waiting on an answer nobody is obliged to send. The due time comes from context.now(),
      // supplied by the runtime — reading a clock here would make the decision untestable and
      // non-repeatable on redelivery.
      return running(
          state.reserved(reserved.reservationId(), Step.AWAITING_PAYMENT),
          Step.AWAITING_PAYMENT,
          "stock-reserved",
          new DispatchCommand(new RequestPayment(orderId, paymentOperationId)),
          new ScheduleDeadline(
              PAYMENT_DEADLINE,
              context.now().plus(paymentTimeout),
              new OrderFulfilmentInput.PaymentTimedOut(orderId)));
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

  /**
   * Stock reserved, waiting for payment: authorise advances to confirm; decline compensates; and if
   * neither arrives, the timer does. Every branch that leaves this step cancels the deadline —
   * leaving it armed would fire a timeout at a flow that has already moved on, and while the
   * generation guard and {@link #ignore} would both absorb it, relying on that is relying on a
   * safety net instead of not falling.
   */
  private ProcessDecision<OrderFulfilmentState> onAwaitingPayment(
      OrderFulfilmentState state, OrderFulfilmentInput in, ProcessContext context) {
    if (in instanceof OrderFulfilmentInput.PaymentAuthorized) {
      return running(
          state.withStep(Step.AWAITING_ORDER_CONFIRMATION),
          Step.AWAITING_ORDER_CONFIRMATION,
          "payment-authorized",
          new DispatchCommand(new ConfirmOrder(state.orderId())),
          new CancelDeadline(PAYMENT_DEADLINE));
    }
    if (in instanceof OrderFulfilmentInput.PaymentDeclined declined) {
      return compensating(
          state.declined(declined.code(), context.cause().messageId(), Step.AWAITING_STOCK_RELEASE),
          Step.AWAITING_STOCK_RELEASE,
          "payment-declined",
          new DispatchCommand(new RequestStockRelease(state.orderId(), state.reservationId())),
          new CancelDeadline(PAYMENT_DEADLINE));
    }
    if (in instanceof OrderFulfilmentInput.PaymentTimedOut) {
      // Silence is an answer. The compensation is the decline's, unchanged — release the stock the
      // order is holding, then cancel it — because the customer's position is identical either way.
      // The recorded code is what tells them apart afterwards, and the evidence id is the timer's
      // own delivery, so the eventual cancellation names the firing rather than a decline that
      // never happened. No deadline is cancelled here: this decision *is* the deadline.
      return compensating(
          state.declined(
              PAYMENT_TIMEOUT_CODE, context.cause().messageId(), Step.AWAITING_STOCK_RELEASE),
          Step.AWAITING_STOCK_RELEASE,
          "payment-timed-out",
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
