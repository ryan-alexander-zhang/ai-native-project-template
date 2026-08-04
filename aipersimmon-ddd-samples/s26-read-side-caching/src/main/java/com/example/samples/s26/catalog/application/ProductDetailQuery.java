package com.example.samples.s26.catalog.application;

import jakarta.validation.constraints.NotBlank;

/**
 * The read this whole sample is about: one product, with a number that costs a scan to produce.
 *
 * <p>It wears {@link CachedQuery}, so the interceptor may answer it without the handler running. Note
 * what the key does <em>not</em> contain: no tenant (prepended by {@code CacheKeys}, so a query cannot
 * name someone else's bucket) and no TTL (deployment policy, in {@code CacheSettings}). What it does
 * contain is every input that changes the answer — here, just the sku. A key that omits an input is the
 * bug that serves one caller's answer to another, and it is invisible until the second input actually
 * differs.
 */
public record ProductDetailQuery(@NotBlank String sku) implements CachedQuery<ProductDetail> {

  @Override
  public String cacheKey() {
    return "product-detail:" + sku;
  }

  @Override
  public Class<ProductDetail> resultType() {
    return ProductDetail.class;
  }
}
