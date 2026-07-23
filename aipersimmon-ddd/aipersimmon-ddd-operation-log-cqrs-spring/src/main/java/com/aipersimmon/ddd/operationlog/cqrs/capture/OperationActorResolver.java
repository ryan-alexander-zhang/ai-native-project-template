package com.aipersimmon.ddd.operationlog.cqrs.capture;

import com.aipersimmon.ddd.operationlog.model.Actor;

/**
 * Resolves the trusted {@link Actor} for the current command from a trusted boundary (security
 * context or explicit invocation scope) — never from the command payload. Implementations must be
 * stateless, do no I/O, and have no side effects; each interceptor calls it to freeze its own
 * snapshot.
 */
@FunctionalInterface
public interface OperationActorResolver {

  /** The actor performing the current command. */
  Actor resolve();
}
