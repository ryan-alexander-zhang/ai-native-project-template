package com.example.samples.s16.ordering.domain;

import com.aipersimmon.ddd.core.annotation.ValueObject;

/** A stock-keeping unit: described, not tracked. */
@ValueObject
public record Sku(String value) {

  public Sku {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("sku must not be blank");
    }
  }
}
