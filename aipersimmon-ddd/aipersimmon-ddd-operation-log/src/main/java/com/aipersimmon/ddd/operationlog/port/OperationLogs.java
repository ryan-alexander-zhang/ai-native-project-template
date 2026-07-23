package com.aipersimmon.ddd.operationlog.port;

import com.aipersimmon.ddd.operationlog.model.OperationLogDraft;

/**
 * The single explicit entry point for business code. It normalizes, adds time/id, validates,
 * redacts, and freezes a {@link OperationLogDraft} into an entry, then appends it via the {@link
 * OperationLogSink}. No entrance (annotation, definition, or direct API) may bypass it to write the
 * sink directly.
 */
public interface OperationLogs {

  /** Normalize, redact, freeze, and append the draft; return whether a row was written. */
  RecordResult record(OperationLogDraft draft);
}
