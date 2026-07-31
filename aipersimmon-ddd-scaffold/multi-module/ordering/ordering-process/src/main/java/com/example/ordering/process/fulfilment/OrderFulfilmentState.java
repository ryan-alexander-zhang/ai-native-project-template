package com.example.ordering.process.fulfilment;

import com.aipersimmon.ddd.processmanager.definition.HasStep;
import com.aipersimmon.ddd.processmanager.model.ProcessStep;

/**
 * The business state of one order-fulfilment flow, carried across inputs by the durable runtime. It
 * holds the facts the flow must remember across hops:
 *
 * <ul>
 *   <li>the {@code reservationId} inventory issued — so the stock can be released on compensation;
 *   <li>the {@code paymentOperationId} the payment request was minted with — so abandoning the
 *       payment wait can void the very operation it opened (issue-00144): by then the causing
 *       envelope is a timer or a cancellation, and the id is not derivable from it;
 *   <li>the {@code paymentDeclineCode} — so the eventual cancellation can name why;
 *   <li>the {@code paymentDeclineEvidenceId} — the <em>envelope id</em> of the causing {@code
 *       PaymentDeclined} fact, kept so the later {@code PaymentDeclineRef} can be identified by the
 *       true event that produced it (not by the order's business key), and so it stays distinct
 *       from the stock-release evidence built in the same cancellation decision.
 * </ul>
 *
 * It is a plain immutable value — the runtime persists it via a state codec, and the runtime
 * lifecycle (RUNNING / COMPENSATING / COMPLETED) is tracked separately, not in this state.
 *
 * @param orderId the order this flow coordinates (its business key)
 * @param step the business step the flow is waiting at
 * @param reservationId the inventory reservation handle, once stock is reserved
 * @param paymentOperationId the operation the payment request opened, once payment is requested
 * @param paymentDeclineCode the payment decline code, once payment is declined
 * @param paymentDeclineEvidenceId the causing decline event's envelope id, once payment is declined
 */
public record OrderFulfilmentState(
    String orderId,
    Step step,
    String reservationId,
    String paymentOperationId,
    String paymentDeclineCode,
    String paymentDeclineEvidenceId)
    implements HasStep {

  /**
   * {@link HasStep}: the decision factories read the step from here, so each decision names its
   * step once — in the state — instead of twice, and the constructor guard keeps the persisted step
   * column from ever drifting away from this state's own.
   */
  @Override
  public ProcessStep processStep() {
    return new ProcessStep(step.name());
  }

  /** Which response the flow is currently waiting for. */
  public enum Step {
    AWAITING_STOCK,
    AWAITING_PAYMENT,
    AWAITING_STOCK_RELEASE,
    AWAITING_ORDER_CONFIRMATION,
    AWAITING_ORDER_CANCELLATION,

    /**
     * Waiting for inventory, for an order the customer has already cancelled.
     *
     * <p>Reachable because {@code READY_FOR_FULFILMENT} is now a state a row actually holds
     * (issue-00070), so the self-cancel window overlaps the reservation. Whether there is stock to
     * hand back is not yet known — that is what this step is still waiting to find out.
     */
    AWAITING_STOCK_ORDER_CANCELLED,

    /**
     * Releasing stock for an order that is already cancelled.
     *
     * <p>Distinct from {@link #AWAITING_STOCK_RELEASE} in exactly one respect, and it is the reason
     * for a separate step rather than a flag: that step ends by dispatching {@code CancelOrder},
     * and here the order has been cancelled already. Dispatching it again would be refused by the
     * aggregate and would poison the effect relay.
     */
    AWAITING_STOCK_RELEASE_ORDER_CANCELLED,

    CONFIRMED,
    CANCELLED
  }

  public OrderFulfilmentState withStep(Step next) {
    return new OrderFulfilmentState(
        orderId,
        next,
        reservationId,
        paymentOperationId,
        paymentDeclineCode,
        paymentDeclineEvidenceId);
  }

  public OrderFulfilmentState reserved(String reservationId, Step next) {
    return new OrderFulfilmentState(
        orderId,
        next,
        reservationId,
        paymentOperationId,
        paymentDeclineCode,
        paymentDeclineEvidenceId);
  }

  /**
   * Records the operation id the flow's {@code RequestPayment} was minted with, so the decision
   * that later abandons the wait can void the same operation (issue-00144).
   */
  public OrderFulfilmentState paymentRequested(String operationId) {
    return new OrderFulfilmentState(
        orderId, step, reservationId, operationId, paymentDeclineCode, paymentDeclineEvidenceId);
  }

  public OrderFulfilmentState declined(String declineCode, String declineEvidenceId, Step next) {
    return new OrderFulfilmentState(
        orderId, next, reservationId, paymentOperationId, declineCode, declineEvidenceId);
  }
}
