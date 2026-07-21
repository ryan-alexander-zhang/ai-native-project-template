package com.example.ordering.domain.order;

import static com.example.ordering.domain.shared.OrderingErrorCode.COMPENSATION_EVIDENCE_ORDER_MISMATCH;
import static com.example.ordering.domain.shared.OrderingErrorCode.CUSTOMER_CANCELLATION_WINDOW_CLOSED;
import static com.example.ordering.domain.shared.OrderingErrorCode.INVENTORY_FAILURE_NOT_APPLICABLE;
import static com.example.ordering.domain.shared.OrderingErrorCode.NOT_ORDER_CUSTOMER;
import static com.example.ordering.domain.shared.OrderingErrorCode.ORDER_NOT_AWAITING_REVIEW;
import static com.example.ordering.domain.shared.OrderingErrorCode.PAYMENT_FAILURE_NOT_APPLICABLE;
import static com.example.ordering.domain.shared.OrderingErrorCode.RESERVATION_FAILURE_ORDER_MISMATCH;
import static com.example.ordering.domain.shared.OrderingErrorCode.RETURN_REQUIRED;
import static com.example.ordering.domain.shared.OrderingErrorCode.REVIEW_DECISION_ORDER_MISMATCH;

import com.aipersimmon.ddd.core.exception.DomainException;
import com.example.ordering.domain.customer.CustomerId;

/**
 * A <em>pure</em> domain policy that decides whether a cancellation is permitted, given only facts
 * the order already owns (its id, its customer, its status) and the evidence carried by the {@link
 * CancellationReason}. It is the rich arbiter for the one transition whose legality depends on
 * <em>why</em> and on <em>proof</em> — everything a flat transition table cannot express.
 *
 * <p>What it must never do: touch a repository, a payment gateway, or the saga store. It only
 * reasons; it never reaches. The aggregate stays the sole mutator — this policy returns by throwing
 * or by returning normally, and {@link Order} performs the actual state change and event.
 */
public final class OrderLifecyclePolicy {

  /**
   * Assert that {@code order (id, customerId, status)} may be cancelled for {@code reason}.
   *
   * @throws DomainException if the cancellation is not permitted
   */
  public void ensureCancellable(
      OrderId orderId, CustomerId customerId, OrderStatus status, CancellationReason reason) {

    // A shipped order is complete; undoing it is a return, not a cancellation. This rule holds
    // regardless of the reason, so it is checked before we branch on the evidence.
    if (status == OrderStatus.SHIPPED) {
      throw new DomainException(
          RETURN_REQUIRED, "a shipped order must enter the return flow, not be cancelled");
    }

    switch (reason) {
      case CancellationReason.CustomerRequested request ->
          ensureCustomerCancellationAllowed(customerId, status, request);
      case CancellationReason.InventoryUnavailable failure ->
          ensureInventoryCancellationAllowed(orderId, status, failure);
      case CancellationReason.PaymentDeclinedAfterStockReleased failure ->
          ensurePaymentCancellationAllowed(orderId, status, failure);
      case CancellationReason.ReviewRejected rejection ->
          ensureReviewCancellationAllowed(orderId, status, rejection);
    }
  }

  private void ensureCustomerCancellationAllowed(
      CustomerId customerId, OrderStatus status, CancellationReason.CustomerRequested request) {

    if (!customerId.equals(request.requestedBy())) {
      throw new DomainException(NOT_ORDER_CUSTOMER, "only the order's own customer may cancel it");
    }
    // The customer's window closes the moment fulfilment starts.
    if (status != OrderStatus.AWAITING_REVIEW && status != OrderStatus.READY_FOR_FULFILMENT) {
      throw new DomainException(
          CUSTOMER_CANCELLATION_WINDOW_CLOSED,
          "the order has entered fulfilment and can no longer be cancelled by the customer");
    }
  }

  private void ensureInventoryCancellationAllowed(
      OrderId orderId, OrderStatus status, CancellationReason.InventoryUnavailable failure) {

    if (status != OrderStatus.FULFILMENT_IN_PROGRESS) {
      throw new DomainException(
          INVENTORY_FAILURE_NOT_APPLICABLE,
          "an inventory failure only cancels an order under fulfilment");
    }
    if (!failure.failure().belongsTo(orderId)) {
      throw new DomainException(
          RESERVATION_FAILURE_ORDER_MISMATCH,
          "the reservation failure does not belong to this order");
    }
  }

  private void ensurePaymentCancellationAllowed(
      OrderId orderId,
      OrderStatus status,
      CancellationReason.PaymentDeclinedAfterStockReleased failure) {

    if (status != OrderStatus.FULFILMENT_IN_PROGRESS) {
      throw new DomainException(
          PAYMENT_FAILURE_NOT_APPLICABLE,
          "a payment failure only cancels an order under fulfilment");
    }
    // Both pieces of compensation evidence must pertain to the very order being cancelled.
    if (!failure.paymentDecline().belongsTo(orderId)
        || !failure.stockRelease().belongsTo(orderId)) {
      throw new DomainException(
          COMPENSATION_EVIDENCE_ORDER_MISMATCH,
          "the compensation evidence does not belong to this order");
    }
  }

  private void ensureReviewCancellationAllowed(
      OrderId orderId, OrderStatus status, CancellationReason.ReviewRejected rejection) {

    if (status != OrderStatus.AWAITING_REVIEW) {
      throw new DomainException(
          ORDER_NOT_AWAITING_REVIEW, "only an order awaiting review can be review-rejected");
    }
    if (!rejection.reviewDecision().belongsTo(orderId)) {
      throw new DomainException(
          REVIEW_DECISION_ORDER_MISMATCH, "the review decision does not belong to this order");
    }
  }
}
