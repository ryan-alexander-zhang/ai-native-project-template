package com.example.samples.s26.catalog.application;

import com.example.samples.s26.catalog.domain.Sku;
import java.util.Optional;

/**
 * The read port: a product detail computed from the source, every time, with no memory.
 *
 * <p>It is the slow path, and it stays the slow path. Nothing here caches, and the adapter behind it
 * does one statement joining the product row to an aggregate over its order lines — which is precisely
 * the read a cache is bought to avoid. Keeping it honest is what makes the measurements mean anything:
 * every trip through this port is counted, so "the cache saved N reads" is a number rather than a claim.
 */
public interface ProductDetails {

  /** The detail as the database currently has it. Empty when no such product exists. */
  Optional<ProductDetail> of(Sku sku);
}
