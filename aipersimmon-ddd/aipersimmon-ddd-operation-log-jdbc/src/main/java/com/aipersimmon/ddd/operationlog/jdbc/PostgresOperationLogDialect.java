package com.aipersimmon.ddd.operationlog.jdbc;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Convergence for PostgreSQL, where any statement error inside a transaction aborts the whole
 * transaction — so a caught duplicate would still doom the caller's commit. Uses {@code INSERT ...
 * ON CONFLICT DO NOTHING}, which is not an error: the update count is 0 on a conflict, leaving the
 * transaction alive so the success-path business changes still commit.
 */
final class PostgresOperationLogDialect implements JdbcOperationLogDialect {

  private static final String INSERT_ON_CONFLICT =
      OperationLogSql.INSERT + " ON CONFLICT (tenant_id, source, idempotency_key) DO NOTHING";

  @Override
  public int insert(JdbcTemplate jdbc, Object[] params) {
    return jdbc.update(INSERT_ON_CONFLICT, params);
  }
}
