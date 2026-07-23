package com.aipersimmon.ddd.operationlog.jdbc;

import com.aipersimmon.ddd.operationlog.model.OperationLogEntry;
import com.aipersimmon.ddd.operationlog.port.AppendResult;
import com.aipersimmon.ddd.operationlog.port.OperationLogSink;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The {@code JdbcTemplate} {@link OperationLogSink}. Appends one row in the caller's transaction; a
 * duplicate idempotency key converges to {@link AppendResult.Duplicate} (returning the existing
 * row's id) via the {@link JdbcOperationLogDialect}, without aborting the transaction. A genuine
 * (non-duplicate) failure propagates so the success-path caller can roll back fail-closed.
 */
public final class JdbcOperationLogSink implements OperationLogSink {

  private final JdbcTemplate jdbc;
  private final JdbcOperationLogDialect dialect;
  private final ObjectMapper objectMapper;

  public JdbcOperationLogSink(
      JdbcTemplate jdbc, JdbcOperationLogDialect dialect, ObjectMapper objectMapper) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    this.dialect = Objects.requireNonNull(dialect, "dialect");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
  }

  /**
   * Build a sink, selecting the dialect from the DataSource. The public wiring entry point (the
   * dialect strategy is an internal detail).
   */
  public static JdbcOperationLogSink create(
      JdbcTemplate jdbc, DataSource dataSource, ObjectMapper objectMapper) {
    return new JdbcOperationLogSink(
        jdbc, OperationLogDialectFactory.create(dataSource), objectMapper);
  }

  @Override
  public AppendResult append(OperationLogEntry entry) {
    int rows = dialect.insert(jdbc, OperationLogSql.params(entry, objectMapper));
    if (rows == 0) {
      return new AppendResult.Duplicate(OperationLogSql.findExistingRecordId(jdbc, entry));
    }
    return new AppendResult.Appended(entry.recordId());
  }
}
