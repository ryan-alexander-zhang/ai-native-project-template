package com.example.inventory.domain.stock;

import com.aipersimmon.ddd.core.annotation.AggregateRoot;
import com.aipersimmon.ddd.core.annotation.Identity;
import com.aipersimmon.ddd.core.exception.DomainException;
import com.aipersimmon.ddd.core.model.AbstractAggregateRoot;

/** The Stock aggregate root: available quantity of one SKU, with a reservation rule. */
@AggregateRoot
public class Stock extends AbstractAggregateRoot<Sku> {

  private final Sku sku;
  private int available;

  public Stock(Sku sku, int available) {
    if (available < 0) {
      throw new DomainException("available must be >= 0");
    }
    this.sku = sku;
    this.available = available;
  }

  /** Reserve the given quantity, guarding against reserving more than is available. */
  public void reserve(int quantity) {
    if (quantity <= 0) {
      throw new DomainException("quantity must be > 0");
    }
    if (quantity > available) {
      // A single-condition guard, so it stays a coded throw — not worth upgrading to
      // an Invariant (design-00003 §4.5). It carries a stable code so a failed
      // reservation surfaces a machine identity even though inventory has no HTTP edge.
      throw new DomainException(
          InventoryErrorCode.INSUFFICIENT_STOCK, "insufficient stock for " + sku.value());
    }
    this.available -= quantity;
  }

  /** Return a previously reserved quantity to the available pool (the compensation for reserve). */
  public void release(int quantity) {
    if (quantity <= 0) {
      throw new DomainException("quantity must be > 0");
    }
    this.available += quantity;
  }

  public int available() {
    return available;
  }

  @Override
  @Identity
  public Sku id() {
    return sku;
  }
}
