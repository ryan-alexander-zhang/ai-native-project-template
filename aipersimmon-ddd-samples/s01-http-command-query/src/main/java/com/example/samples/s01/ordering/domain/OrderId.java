package com.example.samples.s01.ordering.domain;

import com.aipersimmon.ddd.core.annotation.ValueObject;
import com.aipersimmon.ddd.core.model.Identifier;

/** An order's identity. {@code AbstractAggregateRoot} is bound to {@code Identifier}, so an
 * aggregate cannot be declared over a bare String or UUID. */
@ValueObject
public record OrderId(String value) implements Identifier {

  public OrderId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("order id must not be blank");
    }
  }
}
