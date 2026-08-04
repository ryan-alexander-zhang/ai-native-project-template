package com.example.samples.s22.inventory.domain;

import com.aipersimmon.ddd.core.annotation.ValueObject;
import com.aipersimmon.ddd.core.model.Identifier;

/** A stock keeping unit — a natural key the business supplies, so nothing mints it. */
@ValueObject
public record Sku(String value) implements Identifier {

  public Sku {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("sku must not be blank");
    }
  }
}
