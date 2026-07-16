package com.example.ordering.domain.order;

import com.aipersimmon.ddd.core.exception.DomainException;

/**
 * Evidence that a prior stock reservation was released (compensated) for an order.
 * Its existence is the proof {@link OrderLifecyclePolicy} demands before it will accept
 * a {@link CancellationReason.PaymentDeclinedAfterStockReleased} — without a release
 * ref, that cancellation reason cannot even be constructed.
 */
public record StockReleaseRef(String releaseId, OrderId orderId) implements OrderEvidenceRef {

    public StockReleaseRef {
        if (releaseId == null || releaseId.isBlank()) {
            throw new DomainException("stock release id required");
        }
        if (orderId == null) {
            throw new DomainException("stock release must reference an order");
        }
    }
}
