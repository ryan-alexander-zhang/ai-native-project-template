package com.example.ordering.domain.order;

import com.aipersimmon.ddd.core.exception.DomainException;

/**
 * Evidence of a manual-review decision for an order — and the decision's direction is the type, not
 * a field. An {@link Approval} cannot be presented where a {@link Rejection} is required, or vice
 * versa, so the aggregate never has to remember to read a flag to know which way the review went.
 * The same construction as {@link CancellationReason}: what makes evidence trustworthy is that the
 * wrong claim is inexpressible, not that a boolean is conventionally set right. (It used to carry
 * {@code boolean approved}, which no domain code ever read — an {@code approved=false} ref approved
 * an order just the same; issue-00134.)
 */
public sealed interface ReviewDecisionRef extends OrderEvidenceRef {

  /** The review decision's own stable id. */
  String decisionId();

  /** The reviewer cleared the order for fulfilment. */
  record Approval(String decisionId, OrderId orderId) implements ReviewDecisionRef {
    public Approval {
      requireWellFormed(decisionId, orderId);
    }
  }

  /** The reviewer refused the order. */
  record Rejection(String decisionId, OrderId orderId) implements ReviewDecisionRef {
    public Rejection {
      requireWellFormed(decisionId, orderId);
    }
  }

  private static void requireWellFormed(String decisionId, OrderId orderId) {
    if (decisionId == null || decisionId.isBlank()) {
      throw new DomainException("review decision id required");
    }
    if (orderId == null) {
      throw new DomainException("review decision must reference an order");
    }
  }
}
