package com.example.samples.s26.catalog.domain;

import com.aipersimmon.ddd.core.annotation.ValueObject;
import com.aipersimmon.ddd.core.model.Identifier;

/**
 * A product's identity.
 *
 * <p>It is also the free-form part of a cache key, and deliberately allowed to stay free-form: a sku may
 * contain anything a supplier's catalogue contains, and this sample does not get to narrow it. What that
 * costs is paid in {@code CacheKeys} instead — exactly <em>one</em> segment of a joined key may be
 * unconstrained, so the constraint lands on the tenant, which the deployment does control.
 */
@ValueObject
public record Sku(String value) implements Identifier {

  public Sku {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("sku must not be blank");
    }
  }
}
