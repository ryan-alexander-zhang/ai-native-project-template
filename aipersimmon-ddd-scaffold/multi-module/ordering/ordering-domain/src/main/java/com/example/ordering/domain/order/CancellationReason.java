package com.example.ordering.domain.order;

import com.aipersimmon.ddd.core.exception.DomainException;
import com.example.ordering.domain.customer.CustomerId;

/**
 * Why an order is being cancelled — a <em>closed</em> set of reasons, each carrying the evidence
 * that makes it legitimate. This is the type that keeps the fulfilment saga honest: the
 * compensating reason {@link PaymentDeclinedAfterStockReleased} cannot be constructed until the
 * saga actually holds a {@link StockReleaseRef}, so the domain can trust — from the type alone —
 * that stock was released before it cancels the order.
 *
 * <p>A bare enum ({@code PAYMENT_DECLINED}) could not carry that guarantee: it would assert an
 * outcome without any proof the compensation ran.
 */
public sealed interface CancellationReason {

  /** The customer asked to cancel — only they may, and only before fulfilment starts. */
  record CustomerRequested(CustomerId requestedBy) implements CancellationReason {
    public CustomerRequested {
      if (requestedBy == null) {
        throw new DomainException("customer cancellation must name the requesting customer");
      }
    }
  }

  /** Inventory could not reserve stock; the reservation-failure evidence is required. */
  record InventoryUnavailable(ReservationFailureRef failure) implements CancellationReason {
    public InventoryUnavailable {
      if (failure == null) {
        throw new DomainException("inventory cancellation requires reservation-failure evidence");
      }
    }
  }

  /**
   * Payment was declined <em>after</em> the reserved stock was released. Demanding both refs at
   * construction time is the whole point: no stock-release evidence, no reason, no cancellation —
   * the compensation must have happened first.
   */
  record PaymentDeclinedAfterStockReleased(
      PaymentDeclineRef paymentDecline, StockReleaseRef stockRelease)
      implements CancellationReason {
    public PaymentDeclinedAfterStockReleased {
      if (paymentDecline == null) {
        throw new DomainException("payment cancellation requires payment-decline evidence");
      }
      if (stockRelease == null) {
        throw new DomainException(
            "payment cancellation requires stock-release evidence — compensate before cancelling");
      }
    }
  }

  /** Manual review rejected the order; the review decision is required. */
  record ReviewRejected(ReviewDecisionRef reviewDecision) implements CancellationReason {
    public ReviewRejected {
      if (reviewDecision == null) {
        throw new DomainException("review cancellation requires a review decision");
      }
    }
  }
}
