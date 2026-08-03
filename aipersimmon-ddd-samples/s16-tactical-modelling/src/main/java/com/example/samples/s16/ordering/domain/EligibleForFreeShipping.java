package com.example.samples.s16.ordering.domain;

import com.aipersimmon.ddd.core.rule.Specification;

/**
 * Answers a question; it does not refuse anything. Not being eligible for free shipping is an
 * ordinary outcome a caller branches on, so there is no {@code ErrorCode} here — that is the whole
 * difference from an {@link com.aipersimmon.ddd.core.rule.Invariant}.
 */
public record EligibleForFreeShipping(Money threshold) implements Specification<Order> {

  @Override
  public boolean isSatisfiedBy(Order order) {
    return order.total().isGreaterThan(threshold);
  }
}
