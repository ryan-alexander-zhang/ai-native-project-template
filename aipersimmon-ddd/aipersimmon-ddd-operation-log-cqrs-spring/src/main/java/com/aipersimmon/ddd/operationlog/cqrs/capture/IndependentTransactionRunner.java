package com.aipersimmon.ddd.operationlog.cqrs.capture;

/**
 * Runs an action in its own transaction, independent of any caller transaction. The failed
 * interceptor uses it (at the root, where no outer transaction is active) to append the failure
 * record so it survives the business rollback. Injected as a seam; the production binding wraps a
 * {@code TransactionTemplate} with {@code PROPAGATION_REQUIRES_NEW}.
 */
@FunctionalInterface
public interface IndependentTransactionRunner {

  /** Execute {@code action} in an independent transaction. */
  void run(Runnable action);
}
