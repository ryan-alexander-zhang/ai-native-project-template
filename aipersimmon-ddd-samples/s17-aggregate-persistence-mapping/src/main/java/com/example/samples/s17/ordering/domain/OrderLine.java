package com.example.samples.s17.ordering.domain;

import com.aipersimmon.ddd.core.annotation.Entity;
import com.aipersimmon.ddd.core.annotation.Identity;
import java.util.Objects;

/** A line: an entity, so its identity has to survive a write. */
@Entity
public final class OrderLine {

  private final LineId id;
  private final String sku;
  private final Money unitPrice;
  private int quantity;

  OrderLine(LineId id, String sku, Money unitPrice, int quantity) {
    this.id = Objects.requireNonNull(id, "id");
    this.sku = Objects.requireNonNull(sku, "sku");
    this.unitPrice = Objects.requireNonNull(unitPrice, "unitPrice");
    this.quantity = requirePositive(quantity);
  }

  /**
   * Rebuilds a stored line. Public because the repository lives in another package, and separate from
   * the constructor because the two mean different things: one is "a new line was added", this is
   * "a line that already exists is being loaded".
   */
  public static OrderLine restore(LineId id, String sku, Money unitPrice, int quantity) {
    return new OrderLine(id, sku, unitPrice, quantity);
  }

  @Identity
  public LineId id() {
    return id;
  }

  public String sku() {
    return sku;
  }

  public Money unitPrice() {
    return unitPrice;
  }

  public int quantity() {
    return quantity;
  }

  public Money subtotal() {
    return unitPrice.times(quantity);
  }

  void amendQuantity(int newQuantity) {
    this.quantity = requirePositive(newQuantity);
  }

  private static int requirePositive(int quantity) {
    if (quantity <= 0) {
      throw new IllegalArgumentException("quantity must be positive: " + quantity);
    }
    return quantity;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (other == null || getClass() != other.getClass()) {
      return false;
    }
    return id.equals(((OrderLine) other).id);
  }

  @Override
  public int hashCode() {
    return id.hashCode();
  }
}
