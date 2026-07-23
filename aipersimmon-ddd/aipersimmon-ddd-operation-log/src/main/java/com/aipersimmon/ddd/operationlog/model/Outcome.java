package com.aipersimmon.ddd.operationlog.model;

/**
 * The business result of an operation, orthogonal to {@link Completion}. Answers "what did the
 * business decide", not "did the transaction persist".
 */
public enum Outcome {

  /** The operation completed its intended business effect. */
  SUCCEEDED,

  /** The operation was rejected by a business rule, validation, or authorization decision. */
  REJECTED,

  /** The operation failed for a technical reason (handler, append, or commit fault). */
  FAILED
}
