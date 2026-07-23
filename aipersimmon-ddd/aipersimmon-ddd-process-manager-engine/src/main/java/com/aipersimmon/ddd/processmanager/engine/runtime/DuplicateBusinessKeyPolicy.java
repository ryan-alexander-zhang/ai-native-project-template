package com.aipersimmon.ddd.processmanager.engine.runtime;

/**
 * How {@code start} treats a start for an existing {@code (processType, businessKey)} that arrives
 * with a <em>different</em> input message id. A start that repeats the same input message id is
 * always a duplicate no-op regardless of this policy.
 */
public enum DuplicateBusinessKeyPolicy {

  /** Throw {@code ProcessAlreadyExistsException}; the inbound message id is stable. */
  REJECT,
  /** Fold into the existing instance as a duplicate no-op; the transport may re-mint ids. */
  FOLD
}
