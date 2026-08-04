package com.example.samples.s26.catalog.domain;

import com.aipersimmon.ddd.core.annotation.AggregateRoot;
import com.aipersimmon.ddd.core.model.AbstractAggregateRoot;

/**
 * A product: a name and a price, both of which the catalogue owns outright.
 *
 * <p>Two attributes and one invariant is enough, because the subject of this sample is not what a
 * product models but <strong>what may be kept a copy of</strong>. The name and price are the
 * catalogue's own facts, so a cached copy of them is exactly as stale as the last eviction; the sales
 * figure the read side reports alongside them is derived from somewhere else entirely and goes stale on
 * its own. One cached value, two different reasons to distrust it — {@code ProductDetail} is where that
 * gets paid for.
 *
 * <p><strong>This object must not be cached.</strong> Not as a style preference: {@link #version()} is
 * the write path's concurrency token, and a cached instance turns it from a fact about the database
 * into a fact about whoever last put it in the cache. {@code AggregateCacheTrapTest} does it anyway and
 * measures both consequences.
 */
@AggregateRoot
public final class Product extends AbstractAggregateRoot<Sku> {

  private final Sku sku;
  private String name;
  private long priceCents;

  private Product(Sku sku, String name, long priceCents) {
    this.sku = sku;
    this.name = name;
    this.priceCents = priceCents;
  }

  public static Product of(Sku sku, String name, long priceCents) {
    return new Product(sku, requireName(name), requirePrice(priceCents));
  }

  public static Product reconstitute(Sku sku, String name, long priceCents, long version) {
    Product product = new Product(sku, name, priceCents);
    product.restoreVersion(version);
    return product;
  }

  /**
   * Rename it, and say so.
   *
   * @return false when the name is already that. A rename that changes nothing records no event, so it
   *     evicts nothing — which is the right answer twice over: there is no new value to publish, and a
   *     cache entry that is still correct should not be thrown away and paid for again.
   */
  public boolean renameTo(String newName) {
    String wanted = requireName(newName);
    if (wanted.equals(name)) {
      return false;
    }
    this.name = wanted;
    registerEvent(new ProductRenamed(sku, wanted));
    return true;
  }

  /** Reprice it, and say so. Same no-op rule as {@link #renameTo}, for the same two reasons. */
  public boolean repriceTo(long newPriceCents) {
    long wanted = requirePrice(newPriceCents);
    if (wanted == priceCents) {
      return false;
    }
    this.priceCents = wanted;
    registerEvent(new ProductRepriced(sku, wanted));
    return true;
  }

  private static String requireName(String name) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("product name must not be blank");
    }
    return name.strip();
  }

  private static long requirePrice(long priceCents) {
    if (priceCents <= 0) {
      throw new IllegalArgumentException("price must be positive, was " + priceCents);
    }
    return priceCents;
  }

  @Override
  public Sku id() {
    return sku;
  }

  public String name() {
    return name;
  }

  public long priceCents() {
    return priceCents;
  }
}
