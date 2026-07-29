package com.aipersimmon.ddd.tenancy;

/**
 * Switches {@link TenantContext#effective()} into fail-closed mode for as long as a multi-tenant
 * deployment is running.
 *
 * <p>Whether a tenant binding is mandatory is a property of the deployment, not of a request, so it
 * lives in one process-wide flag rather than being passed to every store and interceptor that
 * stamps a {@code tenant_id}. This type is the only sanctioned way to move that flag: the tenancy
 * auto-configurations register it as a bean whose init/destroy methods bracket the application
 * context, so the flag is raised while the context is up and lowered when it closes (which keeps
 * successive contexts in one JVM, as in a test suite, from inheriting each other's mode).
 *
 * <p>Framework-free by design so that every Spring module gating on {@code
 * aipersimmon.ddd.tenancy.enabled} can share one implementation.
 */
public final class TenantEnforcement {

  /** Makes a missing tenant binding a failure rather than a fall back to the sentinel. */
  public void enable() {
    TenantContext.setRequired(true);
  }

  /** Restores sentinel behaviour; called when the owning application context closes. */
  public void disable() {
    TenantContext.setRequired(false);
  }
}
