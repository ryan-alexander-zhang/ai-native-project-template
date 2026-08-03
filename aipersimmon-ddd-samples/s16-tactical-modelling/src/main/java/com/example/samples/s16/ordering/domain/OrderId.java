package com.example.samples.s16.ordering.domain;

import com.aipersimmon.ddd.core.annotation.ValueObject;
import com.aipersimmon.ddd.core.model.Identifier;

/**
 * An order's identity. Every aggregate gets its own identifier type: {@code AbstractAggregateRoot} is
 * bound to {@code Identifier}, which turns "the identities of two aggregates cannot be mixed up" into
 * a compile-time fact rather than a convention.
 */
@ValueObject
public record OrderId(String value) implements Identifier {

  public OrderId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("order id must not be blank");
    }
  }
}
