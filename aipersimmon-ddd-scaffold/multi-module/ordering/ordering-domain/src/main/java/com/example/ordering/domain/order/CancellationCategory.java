package com.example.ordering.domain.order;

/**
 * The coarse, evidence-free classification published on {@link OrderCancelledEvent}. Subscribers
 * get to know <em>that</em> an order was cancelled and broadly why, without the internal evidence
 * refs that the aggregate used to authorise the cancellation.
 */
public enum CancellationCategory {
  CUSTOMER_REQUESTED,
  INVENTORY_UNAVAILABLE,
  PAYMENT_DECLINED,
  REVIEW_REJECTED;

  /** Reduce a rich {@link CancellationReason} to its published category. */
  public static CancellationCategory from(CancellationReason reason) {
    return switch (reason) {
      case CancellationReason.CustomerRequested ignored -> CUSTOMER_REQUESTED;
      case CancellationReason.InventoryUnavailable ignored -> INVENTORY_UNAVAILABLE;
      case CancellationReason.PaymentDeclinedAfterStockReleased ignored -> PAYMENT_DECLINED;
      case CancellationReason.ReviewRejected ignored -> REVIEW_REJECTED;
    };
  }
}
