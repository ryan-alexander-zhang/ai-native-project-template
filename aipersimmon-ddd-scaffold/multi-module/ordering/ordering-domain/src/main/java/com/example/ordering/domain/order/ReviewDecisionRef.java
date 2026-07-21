package com.example.ordering.domain.order;

import com.aipersimmon.ddd.core.exception.DomainException;

/** Evidence of a manual-review decision for an order. */
public record ReviewDecisionRef(String decisionId, OrderId orderId, boolean approved)
    implements OrderEvidenceRef {

  public ReviewDecisionRef {
    if (decisionId == null || decisionId.isBlank()) {
      throw new DomainException("review decision id required");
    }
    if (orderId == null) {
      throw new DomainException("review decision must reference an order");
    }
  }
}
