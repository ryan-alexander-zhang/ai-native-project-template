package com.example.samples.s04.inventory.domain;

import com.aipersimmon.ddd.core.annotation.AggregateRoot;
import com.aipersimmon.ddd.core.model.AbstractAggregateRoot;

/** One sku's stock. The rule that matters: you cannot reserve what is not there. */
@AggregateRoot
public final class StockItem extends AbstractAggregateRoot<Sku> {

  private final Sku sku;
  private int available;
  private int reserved;

  private StockItem(Sku sku, int available, int reserved) {
    this.sku = sku;
    this.available = available;
    this.reserved = reserved;
  }

  public static StockItem stocked(Sku sku, int available) {
    return new StockItem(sku, available, 0);
  }

  public static StockItem reconstitute(Sku sku, int available, int reserved, long version) {
    StockItem item = new StockItem(sku, available, reserved);
    item.restoreVersion(version);
    return item;
  }

  public void reserve(int quantity) {
    checkInvariant(new EnoughStock(sku, available, quantity));
    this.available -= quantity;
    this.reserved += quantity;
  }

  @Override
  public Sku id() {
    return sku;
  }

  public int available() {
    return available;
  }

  public int reserved() {
    return reserved;
  }
}
