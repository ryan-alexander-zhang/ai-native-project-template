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
 * per-command state.
 *
 * <p>{@link #set(TenantId)} and {@link #clear()} are trusted-boundary only — business code must not
 * write to this holder. Programmatic scopes should prefer {@link #runAs(TenantId, Supplier)}.
 *
 * <p>Infrastructure that stamps or filters a tenant column reads {@link #effective()} rather than
 * {@link #current()}, so the "what if nothing is bound" decision is made once, here, from the
 * deployment's {@linkplain #isRequired() tenancy mode} — never re-decided per call site.
 */
public final class TenantContext {

  private static final ThreadLocal<TenantId> CURRENT = new ThreadLocal<>();

  /**
   * Whether a binding is mandatory. Deployment-wide and set once during bootstrap, so it is plain
   * {@code volatile} rather than thread-scoped: it describes the deployment, not the request.
   */
  private static volatile boolean required;

  private TenantContext() {}

  /** The tenant bound to the current thread, if any. */
  public static Optional<TenantId> current() {
    return Optional.ofNullable(CURRENT.get());
  }

  /**
   * The tenant that a tenant-scoped read or write must use.
   *
   * <p>Returns the bound tenant when one is bound. With multi-tenancy switched off it returns the
   * {@link Tenants#ROOT} sentinel, because single-tenant is N=1 multi-tenancy and every row still
   * carries a tenant. With multi-tenancy {@linkplain #isRequired() enabled} and nothing bound it
   * throws {@link MissingTenantException} instead of silently narrowing the operation to the
   * sentinel — a query that quietly returns another bucket's rows (or writes into it) is a data
   * isolation failure, so this path fails closed.
   *
   * @throws MissingTenantException when multi-tenancy is enabled and no tenant is bound
   */
  public static TenantId effective() {
    TenantId tenant = CURRENT.get();
    if (tenant != null) {
      return tenant;
    }
    if (required) {
      throw new MissingTenantException(
          "multi-tenancy is enabled but no tenant is bound to the current thread ("
              + Thread.currentThread().getName()
              + "). A tenant is bound at a trusted boundary (the edge resolution filter or a"
              + " message-consumer entry) and does not cross thread hops on its own: wrap"
              + " asynchronous work in TenantContext.runAs(tenant, ...), or propagate the binding"
              + " with the tenancy TaskDecorator, before touching tenant-scoped data.");
    }
    return Tenants.ROOT;
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

  /**
   * Whether a tenant binding is mandatory for tenant-scoped work, i.e. whether multi-tenancy is
   * enabled for this deployment.
   */
  public static boolean isRequired() {
    return required;
  }

  /**
   * Declares whether a binding is mandatory, switching {@link #effective()} between fail-closed and
   * sentinel behaviour.
   *
   * <p>Package-private on purpose: {@link TenantEnforcement} is the only sanctioned mover — the
   * tenancy auto-configurations register it as a bean whose lifecycle brackets the application
   * context. This used to be public with a javadoc plea ("bootstrap only"), which left the
   * deployment's isolation guarantee flippable by any code in the process at runtime; data
   * isolation is the last property that should rest on discipline, so the compiler now enforces
   * what the comment used to ask for. Tests switch modes through {@code TenantEnforcement} too.
   */
  static void setRequired(boolean value) {
    required = value;
  }
}
