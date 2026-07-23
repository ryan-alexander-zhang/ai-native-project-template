package com.aipersimmon.ddd.operationlog.port;

/**
 * The closed outcome of {@link OperationLogs#record}: a new row was appended, an existing row was
 * found for the same result kind (idempotent duplicate), or the draft was judged not worth
 * recording.
 */
public sealed interface RecordResult
    permits RecordResult.Appended, RecordResult.Duplicate, RecordResult.Skipped {

  /** A new entry was appended. */
  record Appended(String recordId) implements RecordResult {}

  /** An entry with the same idempotency key already existed; its id is returned. */
  record Duplicate(String existingRecordId) implements RecordResult {}

  /** The definition/direct-API caller judged there was nothing to record. */
  record Skipped(String reason) implements RecordResult {}
}
