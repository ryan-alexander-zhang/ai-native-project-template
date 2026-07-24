package com.aipersimmon.ddd.tenancy;

/** Factory and well-known values for {@link TenantId}. */
public final class Tenants {

  /**
   * The prefix reserved for framework sentinels; externally-resolved (user) tenants must never use
   * it, so a resolved tenant can never collide with {@link #ROOT}.
   */
  public static final String RESERVED_PREFIX = "__";

  /**
   * The sentinel tenant used when multi-tenancy is disabled — single-tenant is N=1 multi-tenancy,
   * so this value is stored (never {@code NULL}) on every row of a single-tenant deployment.
   */
  public static final TenantId ROOT = new TenantId("__root__");

  private Tenants() {}

  /**
   * Creates a {@link TenantId} for an externally-resolved (user) tenant, rejecting the reserved
   * {@link #RESERVED_PREFIX} so it can never collide with a framework sentinel such as {@link
   * #ROOT}.
   */
  public static TenantId of(String value) {
    if (value != null && value.startsWith(RESERVED_PREFIX)) {
      throw new IllegalArgumentException(
          "tenant id must not use the reserved '" + RESERVED_PREFIX + "' prefix: " + value);
    }
    return new TenantId(value);
  }

  /**
   * Reconstitutes a {@link TenantId} from a trusted, already-issued value — a persisted row or a
   * wire header, including the {@link #ROOT} sentinel. Unlike {@link #of(String)} it does not
   * reject the reserved prefix, because the value has already passed ingress validation when it was
   * first resolved.
   */
  public static TenantId fromValue(String value) {
    return new TenantId(value);
  }
}
