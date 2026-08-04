package com.example.samples.s24.inventory.domain;

import com.aipersimmon.ddd.core.annotation.AggregateRoot;
import com.aipersimmon.ddd.core.model.AbstractAggregateRoot;

/**
 * Stock for one sku. Deliberately thin — inventory is here to be a third context rather than to be interesting.
 *
 * <p>Its job in this sample is to be the context the new one does <strong>not</strong> talk to. Adding coupons required
 * no change here and created no dependency in either direction, which is the ordinary case and the one worth being able
 * to see: a new context integrates with what it needs and is invisible to the rest.
 */
@AggregateRoot
public final class StockItem extends AbstractAggregateRoot<Sku> {

  private final Sku sku;
  private int onHand;
  private int reserved;

  private StockItem(Sku sku, int onHand, int reserved) {
    this.sku = sku;
    this.onHand = onHand;
    this.reserved = reserved;
  }

  public static StockItem stocked(Sku sku, int onHand) {
    if (onHand < 0) {
      throw new IllegalArgumentException("stock on hand must not be negative");
    }
    return new StockItem(sku, onHand, 0);
  }

  public static StockItem reconstitute(Sku sku, int onHand, int reserved, long version) {
    StockItem item = new StockItem(sku, onHand, reserved);
    item.restoreVersion(version);
    return item;
  }

  /** @return false when there is not enough left, so the caller decides what that means */
  public boolean reserve(int quantity) {
    if (quantity < 1) {
      throw new IllegalArgumentException("a reservation needs at least one unit");
    }
    if (onHand - reserved < quantity) {
      return false;
    }
    reserved += quantity;
    return true;
  }

  @Override
  public Sku id() {
    return sku;
  }

  public int onHand() {
    return onHand;
  }

  public int reserved() {
    return reserved;
  }

  public int available() {
    return onHand - reserved;
  }
}
