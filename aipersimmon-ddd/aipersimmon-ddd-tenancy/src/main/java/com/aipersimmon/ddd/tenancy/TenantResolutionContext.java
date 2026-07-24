package com.aipersimmon.ddd.tenancy;

import java.util.Optional;

/**
 * A framework-free view of an inbound request from which a {@link TenantResolver} reads the tenant.
 * The Spring module adapts an {@code HttpServletRequest} to this interface, keeping the resolver
 * contract free of any servlet dependency.
 */
public interface TenantResolutionContext {

  /** The value of the given request header, if present. */
  Optional<String> header(String name);

  /** The request host, for subdomain-based resolution, if known. */
  Optional<String> host();
}
