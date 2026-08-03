package com.example.samples.s21.inventory.domain;

import com.aipersimmon.ddd.core.annotation.ValueObject;
import com.aipersimmon.ddd.core.model.Identifier;

/**
 * Where a sku is stocked: a sku in a warehouse. A natural composite key the business supplies, so
 * nothing mints it.
 *
 * <p>Stock is per warehouse because the contract's v3 addition had to be observable somewhere. It is
 * also why a record from before that field existed cannot simply be rejected: the business had stock in
 * one warehouse then, and the rule that says which one is the consumer's.
 */
@ValueObject
public record StockLocation(String sku, String warehouse) implements Identifier {

  public StockLocation {
    if (sku == null || sku.isBlank()) {
      throw new IllegalArgumentException("sku must not be blank");
    }
    if (warehouse == null || warehouse.isBlank()) {
      throw new IllegalArgumentException("warehouse must not be blank");
    }
  }

  /** The single-column form the row is keyed by. */
  public String value() {
    return sku + "@" + warehouse;
  }
}
