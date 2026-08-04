package com.example.samples.s12.catalog.domain;

import com.aipersimmon.ddd.core.annotation.ValueObject;
import com.aipersimmon.ddd.core.model.Identifier;

/** A product's identity, and the only thing about a product that other contexts may hold onto. */
@ValueObject
public record Sku(String value) implements Identifier {

  public Sku {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("sku must not be blank");
    }
  }
}
