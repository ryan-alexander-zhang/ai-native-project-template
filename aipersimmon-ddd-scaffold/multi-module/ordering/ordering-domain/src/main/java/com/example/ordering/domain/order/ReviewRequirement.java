package com.example.ordering.domain.order;

import com.aipersimmon.ddd.core.exception.DomainException;
import java.util.Set;

/**
 * The outcome of assessing whether a freshly placed order needs manual review. It is the input
 * {@link Order#place} uses to choose its initial state — {@link OrderStatus#AWAITING_REVIEW} or
 * {@link OrderStatus#READY_FOR_FULFILMENT}. The <em>decision</em> of whether review is needed
 * belongs to a separate {@code ManualReviewPolicy} (order-classification, not lifecycle); this type
 * only carries its verdict into the aggregate.
 */
public sealed interface ReviewRequirement {

  boolean isRequired();

  static ReviewRequirement notRequired() {
    return new NotRequired();
  }

  static ReviewRequirement required(Set<String> reasons) {
    return new Required(reasons);
  }

  record NotRequired() implements ReviewRequirement {
    @Override
    public boolean isRequired() {
      return false;
    }
  }

  record Required(Set<String> reasons) implements ReviewRequirement {
    public Required {
      if (reasons == null || reasons.isEmpty()) {
        throw new DomainException("a review requirement must state at least one reason");
      }
      reasons = Set.copyOf(reasons);
    }

    @Override
    public boolean isRequired() {
      return true;
    }
  }
}
