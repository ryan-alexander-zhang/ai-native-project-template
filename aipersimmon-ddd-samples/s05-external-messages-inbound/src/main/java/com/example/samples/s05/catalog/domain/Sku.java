package com.example.samples.s05.catalog.domain;

import com.aipersimmon.ddd.core.annotation.ValueObject;
import com.aipersimmon.ddd.core.model.Identifier;

/**
 * A product's identity in <em>this</em> context.
 *
 * <p>It happens to hold the same string the ERP calls {@code sku_id}, and that coincidence is the only
 * thing the two systems share. The type exists so the rest of the model never handles a raw upstream
 * string: a translation that leaks its source's primitives has not translated anything.
 */
@ValueObject
public record Sku(String value) implements Identifier {

  public Sku {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("sku must not be blank");
    }
  }
}
