package com.example.samples.s21.ordering.domain;

import com.aipersimmon.ddd.core.annotation.ValueObject;
import com.aipersimmon.ddd.core.model.Identifier;

/** An order's identity, and the partition key of every revision of every event about it. */
@ValueObject
public record OrderId(String value) implements Identifier {

  public OrderId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("order id must not be blank");
    }
  }
}
