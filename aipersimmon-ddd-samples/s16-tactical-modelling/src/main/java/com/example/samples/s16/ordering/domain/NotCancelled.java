package com.example.samples.s16.ordering.domain;

import com.aipersimmon.ddd.core.rule.Specification;

/** Exists mainly to be combined: {@code eligible.and(new NotCancelled())}. */
public record NotCancelled() implements Specification<Order> {

  @Override
  public boolean isSatisfiedBy(Order order) {
    return order.status() != OrderStatus.CANCELLED;
  }
}
