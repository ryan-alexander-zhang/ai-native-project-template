package com.aipersimmon.ddd.operationlog.jdbc;

import com.aipersimmon.ddd.operationlog.exception.OperationLogException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;
import javax.sql.DataSource;

/**
 * Selects the {@link JdbcOperationLogDialect} from the DataSource's product name. Only PostgreSQL
 * needs the {@code ON CONFLICT} strategy; H2 and MySQL use the catch-based default. An unknown
 * product falls back to the default (plain insert + caught duplicate).
 */
final class OperationLogDialectFactory {

  private OperationLogDialectFactory() {}

  static JdbcOperationLogDialect create(DataSource dataSource) {
    return probe(dataSource).contains("postgresql")
        ? new PostgresOperationLogDialect()
        : new DefaultOperationLogDialect();
  }

  private static String probe(DataSource dataSource) {
    try (Connection connection = dataSource.getConnection()) {
      return connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
    } catch (SQLException e) {
      throw new OperationLogException("cannot probe the database product for dialect selection", e);
    }
  }
}
