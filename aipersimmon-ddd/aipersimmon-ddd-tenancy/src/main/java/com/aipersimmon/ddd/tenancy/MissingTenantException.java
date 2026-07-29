package com.aipersimmon.ddd.tenancy;

/**
 * Thrown when multi-tenancy is enabled and a tenant is required but none could be resolved or found
 * bound.
 *
 * <p>Two boundaries raise it: an inbound request that resolves no tenant under {@link
 * MissingTenantPolicy#REJECT}, and {@link TenantContext#effective()} when tenant-scoped work runs
 * on a thread with no binding. The second case is a bug in the caller rather than bad input — a
 * binding was dropped across a thread hop or never established — so it must not be mapped to a
 * client error.
 */
public class MissingTenantException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public MissingTenantException(String message) {
    super(message);
  }
}
