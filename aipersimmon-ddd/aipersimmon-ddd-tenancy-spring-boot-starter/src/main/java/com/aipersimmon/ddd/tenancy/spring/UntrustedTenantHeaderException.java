package com.aipersimmon.ddd.tenancy.spring;

/**
 * Thrown at startup when multi-tenancy is enabled, no {@link
 * com.aipersimmon.ddd.tenancy.TenantResolver} bean is defined, and the deployment has not affirmed
 * that the tenant header can be trusted.
 *
 * <p>Falling back to a client-supplied header would make every tenant's data reachable by anyone
 * who can set one header, so the deployment must state which of the two safe arrangements it uses
 * instead of inheriting a spoofable default.
 */
public class UntrustedTenantHeaderException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public UntrustedTenantHeaderException(String message) {
    super(message);
  }
}
