package com.aipersimmon.ddd.tenancy;

import com.aipersimmon.ddd.core.model.Identifier;

/**
 * The data-isolation boundary identifier — an identified customer/organization whose data must
 * never be visible to another tenant. Immutable and compared by value.
 *
 * <p>Externally opaque: the framework does not mint it (it is resolved from the request) and does
 * not mandate a format. It must be immutable and bounded in length ({@link #MAX_LENGTH}), because
 * it is the leading column of many composite indexes and rides on every durable row — width is a
 * multiplicative cost and a rename would corrupt unique keys and already-dispatched messages.
 * Deliberately not a UUIDv7: its value comes from low-cardinality, rarely-inserted identity, so the
 * time-ordering that helps high-cardinality per-row ids gives it nothing.
 *
 * <p>Construct externally-resolved (user) tenants via {@link Tenants#of(String)} (which rejects the
 * reserved {@code __} prefix); the single-tenant sentinel is {@link Tenants#ROOT}.
 */
public record TenantId(String value) implements Identifier {

  /** Maximum length of a tenant id; kept narrow because it leads many composite indexes. */
  public static final int MAX_LENGTH = 32;

  public TenantId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("tenant id must not be null or blank");
    }
    if (value.length() > MAX_LENGTH) {
      throw new IllegalArgumentException(
          "tenant id must be at most " + MAX_LENGTH + " characters: " + value);
    }
  }

  @Override
  public String toString() {
    return value;
  }
}
