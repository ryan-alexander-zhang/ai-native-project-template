package com.aipersimmon.ddd.processmanager.jdbc.autoconfigure;

import com.aipersimmon.ddd.processmanager.jdbc.lease.AtomicUpdateProcessDialect;
import com.aipersimmon.ddd.processmanager.jdbc.lease.JdbcProcessDialect;
import com.aipersimmon.ddd.processmanager.jdbc.lease.SkipLockedProcessDialect;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;
import javax.sql.DataSource;

/**
 * Selects the {@link JdbcProcessDialect} from configuration or, when {@code auto}, from the
 * DataSource's product name. PostgreSQL and MySQL use {@code SKIP LOCKED}; H2 uses the
 * atomic-update strategy. An unknown database fails fast rather than guessing.
 */
final class ProcessDialectFactory {

  private ProcessDialectFactory() {}

  static JdbcProcessDialect create(String configured, DataSource dataSource) {
    String id =
        "auto".equalsIgnoreCase(configured)
            ? probe(dataSource)
            : configured.toLowerCase(Locale.ROOT);
    return switch (id) {
      case "postgresql" -> new SkipLockedProcessDialect("postgresql");
      case "mysql" -> new SkipLockedProcessDialect("mysql");
      case "h2" -> new AtomicUpdateProcessDialect("h2");
      default ->
          throw new IllegalStateException(
              "unsupported process-manager dialect '"
                  + id
                  + "'; set aipersimmon.ddd.process-manager.jdbc.dialect");
    };
  }

  private static String probe(DataSource dataSource) {
    String product;
    try (Connection connection = dataSource.getConnection()) {
      product = connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
    } catch (SQLException e) {
      throw new IllegalStateException(
          "cannot probe the database product for dialect auto-selection", e);
    }
    if (product.contains("postgresql")) {
      return "postgresql";
    }
    if (product.contains("mysql") || product.contains("maria")) {
      return "mysql";
    }
    if (product.contains("h2")) {
      return "h2";
    }
    throw new IllegalStateException(
        "cannot auto-select a process-manager dialect for database '"
            + product
            + "'; set aipersimmon.ddd.process-manager.jdbc.dialect explicitly");
  }
}
