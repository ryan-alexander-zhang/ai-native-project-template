package com.example.samples.s02.ordering.domain;

import com.aipersimmon.ddd.core.annotation.ValueObject;

/**
 * The caller's own name for this order — a business key, not a transport concern.
 *
 * <p>This is the thing an {@code Idempotency-Key} cannot replace. The key makes one submission safe
 * to repeat; this makes the order unique no matter how many different submissions try to create it,
 * and it is enforced by a UNIQUE constraint rather than by an edge store with a TTL.
 */
@ValueObject
public record ClientReference(String value) {

  public ClientReference {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("client reference must not be blank");
    }
  }
}
