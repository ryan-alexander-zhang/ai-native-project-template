package com.example.samples.s01.ordering.domain;

import com.aipersimmon.ddd.core.annotation.ValueObject;

/** A line of an order. No identity of its own: two lines with the same sku and quantity are the
 * same line. */
@ValueObject
public record OrderLine(String sku, int quantity) {

  public OrderLine {
    if (sku == null || sku.isBlank()) {
      throw new IllegalArgumentException("sku must not be blank");
    }
    if (quantity <= 0) {
      throw new IllegalArgumentException("quantity must be positive: " + quantity);
    }
  }
}
