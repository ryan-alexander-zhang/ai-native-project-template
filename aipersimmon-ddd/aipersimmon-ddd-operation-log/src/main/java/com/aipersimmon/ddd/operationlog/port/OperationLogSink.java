package com.aipersimmon.ddd.operationlog.port;

import com.aipersimmon.ddd.operationlog.model.OperationLogEntry;

/**
 * The outbound write port for a frozen entry, implemented by a storage backend. It is transaction
 * unaware — it simply joins the current transaction; independent-transaction semantics are owned by
 * the interceptor layer. Duplicate-key conflicts converge to {@link AppendResult.Duplicate} using a
 * dialect-native, non-aborting insert on the success path (see design-00008 §7.3).
 */
public interface OperationLogSink {

  /** Append the entry, converging a duplicate idempotency key to {@link AppendResult.Duplicate}. */
  AppendResult append(OperationLogEntry entry);
}
