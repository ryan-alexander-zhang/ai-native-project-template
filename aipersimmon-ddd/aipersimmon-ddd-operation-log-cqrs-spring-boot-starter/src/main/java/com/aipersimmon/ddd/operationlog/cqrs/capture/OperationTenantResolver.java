package com.aipersimmon.ddd.operationlog.cqrs.capture;

/**
 * Resolves the trusted tenant id for the current command from a trusted scope — never from the
 * command payload. Stateless, no I/O, no side effects. When multi-tenancy is disabled, an
 * implementation returns the {@code __root__} sentinel. A default that delegates to the
 * multi-tenancy {@code TenantContext} is auto-configured, so an application only defines this to
 * override that behavior.
 */
@FunctionalInterface
public interface OperationTenantResolver {

  /** The trusted tenant id for the current command. */
  String resolve();
}
