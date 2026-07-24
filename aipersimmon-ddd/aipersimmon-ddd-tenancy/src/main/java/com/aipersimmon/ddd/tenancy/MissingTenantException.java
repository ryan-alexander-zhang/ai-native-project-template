package com.aipersimmon.ddd.tenancy;

/**
 * Thrown when multi-tenancy is enabled, a tenant is required at a trusted boundary, and none could
 * be resolved under {@link MissingTenantPolicy#REJECT}.
 */
public class MissingTenantException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public MissingTenantException(String message) {
    super(message);
  }
}
