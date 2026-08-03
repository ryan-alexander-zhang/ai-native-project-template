package com.example.samples.s08.inventory.domain;

import com.aipersimmon.ddd.core.annotation.ValueObject;
import com.aipersimmon.ddd.core.model.Identifier;

/** A stock item's identity: the sku itself, because that is how the business names it. */
@ValueObject
public record Sku(String value) implements Identifier {

  public Sku {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("sku must not be blank");
    }
  }
}
