package com.aipersimmon.ddd.operationlog.jdbc;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Dialect strategy for inserting one row with idempotent duplicate convergence. Implementations
 * return the number of rows actually inserted: {@code 1} for a new row, {@code 0} when the unique
 * idempotency key already existed. The distinction between dialects is only how a conflict is made
 * non-fatal to the caller's transaction (see the implementations).
 */
interface JdbcOperationLogDialect {

  /** Insert the pre-bound row; return 1 if inserted, 0 if it converged on an existing key. */
  int insert(JdbcTemplate jdbc, Object[] params);
}
