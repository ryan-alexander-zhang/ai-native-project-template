package com.aipersimmon.ddd.operationlog.port;

/**
 * The closed outcome of {@link OperationLogSink#append}: a new row was appended, or a row with the
 * same idempotency key already existed (converged as an idempotent success).
 */
public sealed interface AppendResult permits AppendResult.Appended, AppendResult.Duplicate {

  /** A new row was appended. */
  record Appended(String recordId) implements AppendResult {}

  /** A row with the same idempotency key already existed; its id is returned. */
  record Duplicate(String existingRecordId) implements AppendResult {}
}
