package com.example.samples.s16.ordering.domain;

import com.aipersimmon.ddd.core.annotation.Entity;
import com.aipersimmon.ddd.core.annotation.Identity;
import java.util.Objects;

/**
 * A line of an order: an entity, not a value object, because its quantity can be amended and it is
 * still the same line afterwards. That is the whole test — the concept is <em>tracked</em>, so
 * identity is what makes two lines the same, and {@link Money} next door is described, so its
 * attributes do.
 *
 * <p>Contrast with S1, where a line was a value object and the whole collection was replaced on
 * every write. Neither is wrong; the choice decides how the child rows are written (S17).
 *
 * <p>A non-root entity has no base class to inherit equality from, so it is written out here.
 */
@Entity
public final class OrderLine {

  private final LineId id;
  private final Sku sku;
  private final Money unitPrice;
  private int quantity;

  OrderLine(LineId id, Sku sku, Money unitPrice, int quantity) {
    this.id = Objects.requireNonNull(id, "id");
    this.sku = Objects.requireNonNull(sku, "sku");
    this.unitPrice = Objects.requireNonNull(unitPrice, "unitPrice");
    this.quantity = requirePositive(quantity);
  }

  @Identity
  public LineId id() {
    return id;
  }

  public Sku sku() {
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

  /** Package-private: the aggregate root is the only way in, so it can enforce its own rules first. */
  void amendQuantity(int newQuantity) {
    this.quantity = requirePositive(newQuantity);
  }

  private static int requirePositive(int quantity) {
    if (quantity <= 0) {
      throw new IllegalArgumentException("quantity must be positive: " + quantity);
    }
    return quantity;
  }

  /** Identity equality: the same line, whatever its quantity is now. */
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
