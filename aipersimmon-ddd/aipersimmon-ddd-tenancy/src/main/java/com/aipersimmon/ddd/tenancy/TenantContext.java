package com.aipersimmon.ddd.tenancy;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * The request-scoped ambient holder of the current {@link TenantId}, analogous to SLF4J's MDC.
 *
 * <p>It is bound once at a trusted boundary (the edge resolution filter or a message-consumer
 * entry), read by the read side and by infrastructure where no {@code CommandContext} is threaded,
 * and always cleared when the scope ends. The write-side authority remains {@code
 * CommandContext.tenantId}; this holder carries an immutable request identity, not mutable
 * per-command state (aligned with {@code decision-00012}).
 *
 * <p>{@link #set(TenantId)} and {@link #clear()} are trusted-boundary only — business code must not
 * write to this holder. Programmatic scopes should prefer {@link #runAs(TenantId, Supplier)}.
 */
public final class TenantContext {

  private static final ThreadLocal<TenantId> CURRENT = new ThreadLocal<>();

  private TenantContext() {}

  /** The tenant bound to the current thread, if any. */
  public static Optional<TenantId> current() {
    return Optional.ofNullable(CURRENT.get());
  }

  /** The tenant bound to the current thread, or throws if none is bound. */
  public static TenantId require() {
    TenantId tenant = CURRENT.get();
    if (tenant == null) {
      throw new IllegalStateException("no tenant bound to the current context");
    }
    return tenant;
  }

  /** Binds the tenant for the current thread. Trusted-boundary only. */
  public static void set(TenantId tenant) {
    if (tenant == null) {
      throw new IllegalArgumentException("tenant must not be null");
    }
    CURRENT.set(tenant);
  }

  /** Clears any bound tenant. Trusted-boundary only; call from a finally block. */
  public static void clear() {
    CURRENT.remove();
  }

  /** Runs {@code work} with {@code tenant} bound, restoring the previous binding afterwards. */
  public static <T> T runAs(TenantId tenant, Supplier<T> work) {
    if (tenant == null) {
      throw new IllegalArgumentException("tenant must not be null");
    }
    TenantId previous = CURRENT.get();
    CURRENT.set(tenant);
    try {
      return work.get();
    } finally {
      if (previous == null) {
        CURRENT.remove();
      } else {
        CURRENT.set(previous);
      }
    }
  }

  /** Runs {@code work} with {@code tenant} bound, restoring the previous binding afterwards. */
  public static void runAs(TenantId tenant, Runnable work) {
    runAs(
        tenant,
        () -> {
          work.run();
          return null;
        });
  }
}
