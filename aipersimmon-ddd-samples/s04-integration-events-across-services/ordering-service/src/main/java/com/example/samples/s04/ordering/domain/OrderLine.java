package com.example.samples.s04.ordering.domain;

import com.aipersimmon.ddd.core.annotation.ValueObject;

/** A line of an order. Modelling is S16; this is the minimum the sample needs. */
@ValueObject
public record OrderLine(String sku, int quantity) {

  public OrderLine {
    if (sku == null || sku.isBlank()) {
      throw new IllegalArgumentException("sku must not be blank");
    }
    if (quantity <= 0) {
      throw new IllegalArgumentException("quantity must be positive, was " + quantity);
    }
  }
}
