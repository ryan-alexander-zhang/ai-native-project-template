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
import com.example.ordering.application.order.BeginFulfilment;
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
 * <p><strong>Every step that waits on another context has a timer.</strong> This used to say that
 * payment was the only such step, and the test for it was the wrong one: "will this context refuse
 * me?" Inventory does answer {@code StockReservationFailed} — but only when it judges a
 * <em>business</em> failure. A technical failure (an optimistic-lock conflict against a concurrent
 * reservation, a validation error, a database outage) throws out of its handler and publishes
 * nothing, and from here that silence is indistinguishable from the payment context's. The right
 * test is "can this step's answer fail to arrive?", and for every {@code AWAITING_*} step that
 * waits on a broker the answer is yes (issue-00068).
 *
 * <p>The timeouts are not symmetrical, because what a step can do about silence differs:
 *
 * <ul>
 *   <li>{@code AWAITING_STOCK} — nothing is reserved yet, so a timeout cancels the order outright,
 *       down the same path a reservation failure takes. Only the recorded code differs.
 *   <li>{@code AWAITING_PAYMENT} — stock is held, so a timeout releases it and then cancels, down
 *       the same path a decline takes. The customer's position is identical however the payment
 *       failed to happen.
 *   <li>{@code AWAITING_STOCK_RELEASE} — a timeout <em>cannot</em> end the wait, and this is the
 *       interesting one. Cancelling from here needs a {@link
 *       CancellationReason.PaymentDeclinedAfterStockReleased}, which cannot be constructed without
 *       a {@link StockReleaseRef} — evidence the stock came back. A timeout is exactly the absence
 *       of that evidence. So the flow re-sends the release request and re-arms the timer: it keeps
 *       asking rather than either fabricating evidence or sitting silent. {@code ReleaseStock} is
 *       idempotent, and rescheduling a deadline by name supersedes the previous generation, so
 *       neither the repeat nor the timer accumulates anything.
 * </ul>
 *
 * <p>The third case is where the evidence-bearing cancellation reason earns its complexity: a
 * design that let the flow cancel without proof would have "recovered" here by declaring released
 * stock that is still held.
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

  /** The timer armed while the reservation is outstanding. Same naming rationale as PAYMENT. */
  static final DeadlineName STOCK_DEADLINE = new DeadlineName("STOCK");

  /**
   * The timer armed while a compensating release is outstanding. Re-armed on every firing, since
   * this wait can only end when the release actually happens.
   */
  static final DeadlineName STOCK_RELEASE_DEADLINE = new DeadlineName("STOCK_RELEASE");

  /** Recorded as the decline code when the timer, not the payment context, ended the wait. */
  static final String PAYMENT_TIMEOUT_CODE = "PAYMENT_TIMEOUT";

  /** Recorded as the failure code when the timer, not inventory, ended the reservation wait. */
  static final String STOCK_TIMEOUT_CODE = "STOCK_TIMEOUT";

  /**
   * How long each outstanding answer may take before the flow acts on the silence. Held as fields
   * rather than read from a clock or a property inside {@link #react}, so the decision stays a
   * function of its arguments.
   *
   * <p>The reservation budget is shorter than payment's by default: reserving stock is a local
   * decision in a context we operate, while authorising a payment waits on a third party. A
   * deployment sets both from what its dependencies actually promise.
   */
  private final Duration paymentTimeout;

  private final Duration stockTimeout;

  private final Duration stockReleaseTimeout;

  public OrderFulfilmentDefinition(
      @Value("${ordering.fulfilment.payment-timeout:PT2M}") Duration paymentTimeout,
      @Value("${ordering.fulfilment.stock-timeout:PT1M}") Duration stockTimeout,
      @Value("${ordering.fulfilment.stock-release-timeout:PT1M}") Duration stockReleaseTimeout) {
    this.paymentTimeout = paymentTimeout;
    this.stockTimeout = stockTimeout;
    this.stockReleaseTimeout = stockReleaseTimeout;
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
      // Starting the flow and arming its first timer are one decision, for the same reason asking
      // for payment and arming that timer are: a wait must not be able to begin without a way out.
      return running(
          state,
          Step.AWAITING_STOCK,
          "ready-for-fulfilment",
          new ScheduleDeadline(
              STOCK_DEADLINE,
              context.now().plus(stockTimeout),
              new OrderFulfilmentInput.StockReservationTimedOut(ready.orderId())));
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
      case AWAITING_STOCK_ORDER_CANCELLED -> onAwaitingStockOrderCancelled(state, in, context);
      case AWAITING_PAYMENT -> onAwaitingPayment(state, in, context);
      case AWAITING_STOCK_RELEASE -> onAwaitingStockRelease(state, in, context);
      case AWAITING_STOCK_RELEASE_ORDER_CANCELLED ->
          onAwaitingStockReleaseOrderCancelled(state, in, context);
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
          // The reservation exists, so now — and only now — the order really is under fulfilment.
          // Ordering's own state used to be advanced at placement, before anyone had reserved
          // anything (issue-00070).
          new DispatchCommand(new BeginFulfilment(orderId)),
          new DispatchCommand(new RequestPayment(orderId, paymentOperationId)),
          new CancelDeadline(STOCK_DEADLINE),
          new ScheduleDeadline(
              PAYMENT_DEADLINE,
              context.now().plus(paymentTimeout),
              new OrderFulfilmentInput.PaymentTimedOut(orderId)));
    }
    if (in instanceof OrderFulfilmentInput.StockReservationFailed failed) {
      return cancelForInventory(
          state, context, failed.code(), failed.reason(), "stock-reservation-failed", true);
    }
    if (in instanceof OrderFulfilmentInput.OrderCancelled) {
      // The customer used their self-cancel window while inventory was still working. Nothing can
      // be concluded yet: the reservation may already exist, may be about to, or may fail. So the
      // flow keeps waiting — with its STOCK deadline deliberately still armed, since a cancelled
      // order whose inventory never answers must not wait forever either.
      return compensating(
          state.withStep(Step.AWAITING_STOCK_ORDER_CANCELLED),
          Step.AWAITING_STOCK_ORDER_CANCELLED,
          "order-cancelled-while-awaiting-stock");
    }
    if (in instanceof OrderFulfilmentInput.StockReservationTimedOut) {
      // Silence from inventory, handled exactly as a refusal from inventory: nothing was reserved,
      // so there is nothing to release and the order is cancelled outright. Only the code differs,
      // which is what lets an operator tell "inventory said no" from "inventory said nothing".
      // No deadline is cancelled here — this decision *is* the deadline.
      return cancelForInventory(
          state,
          context,
          STOCK_TIMEOUT_CODE,
          "inventory did not answer within " + stockTimeout,
          "stock-reservation-timed-out",
          false);
    }
    return ignore(state, in, context);
  }

  /**
   * The one compensation both inventory outcomes take: cancel the order, carrying the failure as
   * evidence. A refusal and a silence differ only in their code and in whether a timer is still
   * armed, so they share the branch rather than duplicating it — the same economy the payment
   * timeout gets from reusing the decline's path.
   */
  private ProcessDecision<OrderFulfilmentState> cancelForInventory(
      OrderFulfilmentState state,
      ProcessContext context,
      String code,
      String reason,
      String decisionCode,
      boolean cancelTimer) {
    ReservationFailureRef failure =
        new ReservationFailureRef(
            context.cause().messageId(), new OrderId(state.orderId()), code, reason);
    DispatchCommand cancel =
        new DispatchCommand(
            new CancelOrder(state.orderId(), new CancellationReason.InventoryUnavailable(failure)));
    return cancelTimer
        ? compensating(
            state.withStep(Step.AWAITING_ORDER_CANCELLATION),
            Step.AWAITING_ORDER_CANCELLATION,
            decisionCode,
            cancel,
            new CancelDeadline(STOCK_DEADLINE))
        : compensating(
            state.withStep(Step.AWAITING_ORDER_CANCELLATION),
            Step.AWAITING_ORDER_CANCELLATION,
            decisionCode,
            cancel);
  }

  /**
   * The order is already cancelled and inventory has not answered yet. Only one question remains —
   * is there stock to give back? — so there are exactly two ways out and neither involves the
   * order: it is in its terminal state already.
   */
  private ProcessDecision<OrderFulfilmentState> onAwaitingStockOrderCancelled(
      OrderFulfilmentState state, OrderFulfilmentInput in, ProcessContext context) {
    if (in instanceof OrderFulfilmentInput.StockReserved reserved) {
      // Reserved for an order that no longer exists: hand it straight back. No BeginFulfilment and
      // no RequestPayment — the cancellation happened while the order was still the customer's to
      // cancel, and it stands.
      return compensating(
          state.reserved(reserved.reservationId(), Step.AWAITING_STOCK_RELEASE_ORDER_CANCELLED),
          Step.AWAITING_STOCK_RELEASE_ORDER_CANCELLED,
          "stock-reserved-for-cancelled-order",
          new DispatchCommand(new RequestStockRelease(state.orderId(), reserved.reservationId())),
          new CancelDeadline(STOCK_DEADLINE),
          releaseDeadline(state, context));
    }
    if (in instanceof OrderFulfilmentInput.StockReservationFailed
        || in instanceof OrderFulfilmentInput.StockReservationTimedOut) {
      // Nothing was ever reserved and the order is already cancelled, so there is no compensation
      // to run and no command to send. The flow is simply finished.
      boolean timedOut = in instanceof OrderFulfilmentInput.StockReservationTimedOut;
      return completed(
          state.withStep(Step.CANCELLED),
          Step.CANCELLED,
          timedOut ? "cancelled-order-stock-timed-out" : "cancelled-order-stock-failed",
          "ORDER_CANCELLED");
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
          new CancelDeadline(PAYMENT_DEADLINE),
          releaseDeadline(state, context));
    }
    if (in instanceof OrderFulfilmentInput.OrderCancelled) {
      // The residual window: the customer's cancellation committed while this flow was moving from
      // AWAITING_STOCK to here, so BeginFulfilment found a cancelled order and did nothing. Stock
      // is held, and the order is terminal — release it and finish, without a second CancelOrder.
      return compensating(
          state.withStep(Step.AWAITING_STOCK_RELEASE_ORDER_CANCELLED),
          Step.AWAITING_STOCK_RELEASE_ORDER_CANCELLED,
          "order-cancelled-while-awaiting-payment",
          new DispatchCommand(new RequestStockRelease(state.orderId(), state.reservationId())),
          new CancelDeadline(PAYMENT_DEADLINE),
          releaseDeadline(state, context));
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
          new DispatchCommand(new RequestStockRelease(state.orderId(), state.reservationId())),
          releaseDeadline(state, context));
    }
    return ignore(state, in, context);
  }

  /** Arms (or re-arms) the timer that watches an outstanding release request. */
  private ScheduleDeadline releaseDeadline(OrderFulfilmentState state, ProcessContext context) {
    return new ScheduleDeadline(
        STOCK_RELEASE_DEADLINE,
        context.now().plus(stockReleaseTimeout),
        new OrderFulfilmentInput.StockReleaseTimedOut(state.orderId()));
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
          new DispatchCommand(new CancelOrder(state.orderId(), reason)),
          new CancelDeadline(STOCK_RELEASE_DEADLINE));
    }
    if (in instanceof OrderFulfilmentInput.StockReleaseTimedOut) {
      // The only timeout here that does not end its wait. Cancelling needs a StockReleaseRef and a
      // timeout is the absence of one, so the flow asks again instead: re-send the release, re-arm
      // the timer, stay where it is. ReleaseStock is idempotent (the reservation carries a
      // `released` flag), and rescheduling by name supersedes the previous generation, so neither
      // repeats nor timers accumulate — and the moment inventory answers, the flow completes
      // normally.
      //
      // Deliberately unbounded. Giving up would mean recording that stock came back when it has
      // not; a flow that keeps asking is visible as a long-lived COMPENSATING instance, which is
      // what the process backlog metrics are for. A deployment that wants a hard stop arms
      // instance.max-lifetime and handles MaxLifetimeExceeded in react (see below).
      return compensating(
          state,
          Step.AWAITING_STOCK_RELEASE,
          "stock-release-timed-out",
          new DispatchCommand(new RequestStockRelease(state.orderId(), state.reservationId())),
          releaseDeadline(state, context));
    }
    return ignore(state, in, context);
  }

  /**
   * Releasing stock for an order that was already cancelled. Identical to {@link
   * #onAwaitingStockRelease} except at the end: there is no {@code CancelOrder} to dispatch, so the
   * release itself is the last thing that had to happen.
   */
  private ProcessDecision<OrderFulfilmentState> onAwaitingStockReleaseOrderCancelled(
      OrderFulfilmentState state, OrderFulfilmentInput in, ProcessContext context) {
    if (in instanceof OrderFulfilmentInput.StockReleased) {
      return completed(
          state.withStep(Step.CANCELLED),
          Step.CANCELLED,
          "stock-released-for-cancelled-order",
          "ORDER_CANCELLED",
          new CancelDeadline(STOCK_RELEASE_DEADLINE));
    }
    if (in instanceof OrderFulfilmentInput.StockReleaseTimedOut) {
      // Same reasoning as the other release step: this wait cannot be ended, only satisfied, so
      // the flow asks again rather than declaring stock returned that is still held.
      return compensating(
          state,
          Step.AWAITING_STOCK_RELEASE_ORDER_CANCELLED,
          "cancelled-order-stock-release-timed-out",
          new DispatchCommand(new RequestStockRelease(state.orderId(), state.reservationId())),
          releaseDeadline(state, context));
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
      OrderFulfilmentState state,
      Step step,
      String code,
      String outcome,
      ProcessEffect... effects) {
    return decision(
        state,
        ProcessLifecycle.COMPLETED,
        step,
        Optional.of(new ProcessOutcome(outcome)),
        code,
        effects);
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
