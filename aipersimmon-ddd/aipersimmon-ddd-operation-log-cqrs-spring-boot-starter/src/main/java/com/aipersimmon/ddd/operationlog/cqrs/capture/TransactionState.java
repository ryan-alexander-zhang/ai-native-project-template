package com.aipersimmon.ddd.operationlog.cqrs.capture;

/**
 * Reports whether an actual transaction is active on the current thread. The failed interceptor
 * checks it on entry: a root dispatch has none (its transaction starts further in), whereas a
 * nested child dispatched from inside a parent's transaction sees the parent's — so the child
 * defers its failure record to the root. Injected as a seam so the interceptor is unit-testable;
 * the production binding is {@code TransactionSynchronizationManager::isActualTransactionActive}.
 */
@FunctionalInterface
public interface TransactionState {

  /** Whether an actual transaction is active on the current thread. */
  boolean hasActiveTransaction();
}
