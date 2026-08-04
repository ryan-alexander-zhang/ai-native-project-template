package com.example.samples.s12.catalog.domain;

import com.aipersimmon.ddd.core.annotation.AggregateRoot;
import com.aipersimmon.ddd.core.model.AbstractAggregateRoot;

/**
 * A product, reduced to the one attribute this sample is about: its display name.
 *
 * <p>Small on purpose. The interesting thing about the catalogue in S12 is not what it models but what it
 * owns — <strong>the current name of a product is the catalogue's fact, and every other context's copy of
 * it is stale by definition.</strong> The rename below is the event that makes that concrete.
 */
@AggregateRoot
public final class Product extends AbstractAggregateRoot<Sku> {

  private final Sku sku;
  private String name;

  private Product(Sku sku, String name) {
    this.sku = sku;
    this.name = name;
  }

  public static Product of(Sku sku, String name) {
    return new Product(sku, requireName(name));
  }

  public static Product reconstitute(Sku sku, String name, long version) {
    Product product = new Product(sku, name);
    product.restoreVersion(version);
    return product;
  }

  /**
   * Rename it.
   *
   * @return false when the name is already that, which matters more than it looks: a rename that changes
   *     nothing must not publish an event, or every idempotent retry upstream becomes a broadcast and
   *     every consumer's projection is rewritten for nothing.
   */
  public boolean renameTo(String newName) {
    String wanted = requireName(newName);
    if (wanted.equals(name)) {
      return false;
    }
    this.name = wanted;
    return true;
  }

  private static String requireName(String name) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("product name must not be blank");
    }
    return name.strip();
  }

  @Override
  public Sku id() {
    return sku;
  }

  public String name() {
    return name;
  }
}
