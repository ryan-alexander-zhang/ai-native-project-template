package com.aipersimmon.ddd.operationlog.jdbc;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Convergence for engines whose constraint violation is a statement-level error that does not abort
 * the surrounding transaction (H2, MySQL): a plain insert, catching the duplicate and reporting
 * zero rows. Must not be used on PostgreSQL, where a violation aborts the whole transaction.
 */
final class DefaultOperationLogDialect implements JdbcOperationLogDialect {

  @Override
  public int insert(JdbcTemplate jdbc, Object[] params) {
    try {
      return jdbc.update(OperationLogSql.INSERT, params);
    } catch (DuplicateKeyException duplicate) {
      return 0;
    }
  }
}
