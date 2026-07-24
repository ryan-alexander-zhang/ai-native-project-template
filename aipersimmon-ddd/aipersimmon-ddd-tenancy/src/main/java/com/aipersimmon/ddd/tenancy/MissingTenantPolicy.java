package com.aipersimmon.ddd.tenancy;

/** What to do when multi-tenancy is enabled but no tenant resolves from a request. */
public enum MissingTenantPolicy {

  /** Reject the request — the safe default; never silently fall back to a shared bucket. */
  REJECT,

  /** Fall back to the sentinel {@link Tenants#ROOT}; controlled internal/migration use only. */
  SYSTEM
}
