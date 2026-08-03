package com.example.samples.s21.inventory.domain;

import com.aipersimmon.ddd.core.annotation.AggregateRoot;
import com.aipersimmon.ddd.core.model.AbstractAggregateRoot;

/** One sku's stock in one warehouse. The rule that matters: you cannot reserve what is not there. */
@AggregateRoot
public final class StockItem extends AbstractAggregateRoot<StockLocation> {

  private final StockLocation location;
  private int available;
  private int reserved;

  private StockItem(StockLocation location, int available, int reserved) {
    this.location = location;
    this.available = available;
    this.reserved = reserved;
  }

  public static StockItem stocked(StockLocation location, int available) {
    return new StockItem(location, available, 0);
  }

  public static StockItem reconstitute(
      StockLocation location, int available, int reserved, long version) {
    StockItem item = new StockItem(location, available, reserved);
    item.restoreVersion(version);
    return item;
  }

  public void reserve(int quantity) {
    checkInvariant(new EnoughStock(location, available, quantity));
    this.available -= quantity;
    this.reserved += quantity;
  }

  @Override
  public StockLocation id() {
    return location;
  }

  public int available() {
    return available;
  }

  public int reserved() {
    return reserved;
  }
}
