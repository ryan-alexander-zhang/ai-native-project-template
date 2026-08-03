package com.example.samples.s02.ordering.domain;

import com.aipersimmon.ddd.core.annotation.ValueObject;
import com.aipersimmon.ddd.core.model.Identifier;

/** An order's identity, minted by the application from the framework's UUIDv7 generator. */
@ValueObject
public record OrderId(String value) implements Identifier {

  public OrderId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("order id must not be blank");
    }
  }
}
