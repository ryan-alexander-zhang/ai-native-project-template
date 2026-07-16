package com.example.ordering.domain.order;

import com.aipersimmon.ddd.core.exception.DomainException;

/** Evidence that payment was declined for an order, translated into Ordering's terms. */
public record PaymentDeclineRef(String declineId, OrderId orderId, String declineCode)
        implements OrderEvidenceRef {

    public PaymentDeclineRef {
        if (declineId == null || declineId.isBlank()) {
            throw new DomainException("payment decline id required");
        }
        if (orderId == null) {
            throw new DomainException("payment decline must reference an order");
        }
        if (declineCode == null || declineCode.isBlank()) {
            throw new DomainException("payment decline must carry a translated decline code");
        }
    }
}
