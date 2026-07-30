package com.aipersimmon.ddd.processmanager.jdbc.autoconfigure;

import com.aipersimmon.ddd.processmanager.engine.autoconfigure.ProcessVendors;
import com.aipersimmon.ddd.processmanager.jdbc.lease.AtomicUpdateProcessDialect;
import com.aipersimmon.ddd.processmanager.jdbc.lease.JdbcProcessDialect;
import com.aipersimmon.ddd.processmanager.jdbc.lease.SkipLockedProcessDialect;
import java.util.Locale;
import javax.sql.DataSource;

/**
 * Selects the {@link JdbcProcessDialect} from configuration or, when {@code auto}, from the
 * DataSource's product name (see {@link ProcessVendors}, which both storage backends share so the
 * two can never answer differently). PostgreSQL and MySQL use {@code SKIP LOCKED}; H2 uses the
 * atomic-update strategy. An unknown database fails fast rather than guessing.
 */
final class ProcessDialectFactory {

  private static final String DIALECT_PROPERTY = "aipersimmon.ddd.process-manager.jdbc.dialect";

  private ProcessDialectFactory() {}

  static JdbcProcessDialect create(String configured, DataSource dataSource) {
    String id =
        "auto".equalsIgnoreCase(configured)
            ? ProcessVendors.probe(dataSource, DIALECT_PROPERTY)
            : configured.toLowerCase(Locale.ROOT);
    return switch (id) {
      case "postgresql" -> new SkipLockedProcessDialect("postgresql");
      case "mysql" -> new SkipLockedProcessDialect("mysql");
      case "h2" -> new AtomicUpdateProcessDialect("h2");
      default ->
          throw new IllegalStateException(
              "unsupported process-manager dialect '" + id + "'; set " + DIALECT_PROPERTY);
    };
  }
}
