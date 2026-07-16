package com.example.ordering.domain.order;

import com.aipersimmon.ddd.core.exception.DomainException;

/**
 * Evidence that inventory could not reserve stock for an order. The {@code reasonCode}
 * and {@code detail} are the inventory failure <em>translated into Ordering's own terms</em>
 * at the anti-corruption boundary — closing the gap where a cross-context error code
 * would otherwise be dropped on the way in.
 */
public record ReservationFailureRef(String failureId, OrderId orderId, String reasonCode, String detail)
        implements OrderEvidenceRef {

    public ReservationFailureRef {
        if (failureId == null || failureId.isBlank()) {
            throw new DomainException("reservation failure id required");
        }
        if (orderId == null) {
            throw new DomainException("reservation failure must reference an order");
        }
        if (reasonCode == null || reasonCode.isBlank()) {
            throw new DomainException("reservation failure must carry a translated reason code");
        }
    }
}
