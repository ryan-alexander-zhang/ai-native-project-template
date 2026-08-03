package com.example.samples.s16.ordering.domain;

import com.aipersimmon.ddd.core.annotation.ValueObject;
import com.aipersimmon.ddd.core.model.Identifier;

/**
 * A customer's identity. The order holds this rather than a {@code Customer}: aggregates reference
 * each other only by identity, because holding the other root would pull its consistency rules into
 * this transaction.
 */
@ValueObject
public record CustomerId(String value) implements Identifier {

  public CustomerId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("customer id must not be blank");
    }
  }
}
