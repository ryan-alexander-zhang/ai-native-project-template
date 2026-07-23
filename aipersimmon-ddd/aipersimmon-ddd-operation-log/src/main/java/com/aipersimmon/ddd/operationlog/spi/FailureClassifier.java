package com.aipersimmon.ddd.operationlog.spi;

import com.aipersimmon.ddd.operationlog.model.OperationLogInvocation;

/**
 * Maps a throwable surfaced on the failure path to a {@link ClassifiedOutcome} (business outcome +
 * sanitized failure). The default implementation (in the engine) aligns with the repository's
 * exception model: expected business/validation/authorization exceptions become {@code REJECTED},
 * concurrency conflicts and other technical faults become {@code FAILED}. A consumer may override
 * it to map its own domain exceptions.
 */
@FunctionalInterface
public interface FailureClassifier {

  /** Classify a failure into a business outcome and sanitized facts. */
  ClassifiedOutcome classify(Throwable failure, OperationLogInvocation invocation);
}
