package com.aipersimmon.ddd.operationlog.port;

/**
 * The read port, separate from the write path. Implemented by a storage backend in P3. Criteria
 * must carry a tenant and bounded time/target conditions; results are cursor-paginated (no
 * unbounded list). Query authorization and shaping are the consumer adapter's responsibility.
 */
public interface OperationLogReader {

  /** Return one page of entries matching the criteria, starting from the cursor. */
  OperationLogPage find(OperationLogCriteria criteria, OperationLogCursor cursor);
}
