package com.aipersimmon.ddd.operationlog.model;

/**
 * Whether the business transaction that carried the operation took effect, orthogonal to {@link
 * Outcome}. A normal return may be {@code REJECTED + COMMITTED}; an exception may be {@code
 * REJECTED + NOT_STARTED} or {@code FAILED + ROLLED_BACK}.
 */
public enum Completion {

  /** The business transaction committed. */
  COMMITTED,

  /** The business transaction rolled back. */
  ROLLED_BACK,

  /** No business transaction was started (e.g. rejected before it began). */
  NOT_STARTED,

  /** The completion state cannot be proven (e.g. a direct-API call outside any transaction). */
  UNKNOWN
}
