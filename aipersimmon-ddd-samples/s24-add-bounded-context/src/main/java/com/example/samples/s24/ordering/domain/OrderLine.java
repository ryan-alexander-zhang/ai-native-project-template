package com.example.samples.s24.ordering.domain;

import com.aipersimmon.ddd.core.annotation.ValueObject;
import com.example.samples.s24.sharedkernel.api.Money;

/**
 * One line of an order.
 *
 * <p>{@code sku} is a plain string, which is a deliberate small decision: the inventory context owns what a sku is, and
 * ordering holds one without asking. Publishing an {@code Sku} type from inventory would be defensible; leaving it as a
 * string here is the choice a real service usually starts with, and the sample would rather show one of each than
 * pretend every reference gets a type on day one.
 */
@ValueObject
public record OrderLine(int lineNo, String sku, int quantity, Money unitPrice) {

  public OrderLine {
    if (lineNo < 1) {
      throw new IllegalArgumentException("line numbers start at 1");
    }
    if (sku == null || sku.isBlank()) {
      throw new IllegalArgumentException("a line needs a sku");
    }
    if (quantity < 1) {
      throw new IllegalArgumentException("a line needs at least one unit");
    }
  }

  public Money lineTotal() {
    return unitPrice.times(quantity);
  }
}
