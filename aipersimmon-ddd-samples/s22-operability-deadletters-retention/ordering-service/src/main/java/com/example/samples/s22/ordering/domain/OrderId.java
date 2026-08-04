package com.example.samples.s22.ordering.domain;

import com.aipersimmon.ddd.core.annotation.ValueObject;
import com.aipersimmon.ddd.core.model.Identifier;

/**
 * An order's identity — and, when an event about it is published, the {@code subject} an operator
 * searches a dead letter by. A give-up that could not be tied back to an aggregate would be a row
 * nobody can act on.
 */
@ValueObject
public record OrderId(String value) implements Identifier {

  public OrderId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("order id must not be blank");
    }
  }
}
