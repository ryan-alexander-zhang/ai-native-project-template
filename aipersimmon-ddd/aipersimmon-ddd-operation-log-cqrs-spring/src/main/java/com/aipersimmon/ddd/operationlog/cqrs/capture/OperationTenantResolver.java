package com.aipersimmon.ddd.operationlog.cqrs.capture;

/**
 * Resolves the trusted tenant id for the current command from a trusted scope — never from the
 * command payload. Stateless, no I/O, no side effects. When multi-tenancy is disabled, an
 * implementation returns the {@code GLOBAL} normalization.
 */
@FunctionalInterface
public interface OperationTenantResolver {

  /** The trusted tenant id for the current command. */
  String resolve();
}
