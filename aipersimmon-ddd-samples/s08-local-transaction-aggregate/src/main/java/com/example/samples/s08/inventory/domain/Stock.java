package com.example.samples.s08.inventory.domain;

import com.aipersimmon.ddd.core.annotation.AggregateRoot;
import com.aipersimmon.ddd.core.model.AbstractAggregateRoot;

/**
 * One sku's stock. The consistency boundary is one sku, because "you cannot reserve more than is
 * available" is a rule about one sku and nothing else.
 */
@AggregateRoot
public final class Stock extends AbstractAggregateRoot<Sku> {

  private final Sku sku;
  private int available;

  private Stock(Sku sku, int available) {
    this.sku = sku;
    this.available = available;
  }

  public static Stock of(Sku sku, int available) {
    return new Stock(sku, available);
  }

  public static Stock reconstitute(Sku sku, int available, long version) {
    Stock stock = new Stock(sku, available);
    stock.restoreVersion(version);
    return stock;
  }

  public void reserve(int quantity) {
    if (quantity <= 0) {
      throw new IllegalArgumentException("quantity must be positive: " + quantity);
    }
    checkInvariant(new EnoughStock(sku, available, quantity));
    this.available -= quantity;
  }

  @Override
  public Sku id() {
    return sku;
  }

  public int available() {
    return available;
  }
}
