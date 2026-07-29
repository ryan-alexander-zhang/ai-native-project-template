package com.aipersimmon.ddd.processmanager.jdbc.autoconfigure;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Verifies at startup that the four process tables exist and are current, so a missing or stale
 * migration fails fast with a clear message instead of at the first advance. It never creates
 * tables — the DDL ships as a sample and is applied via Flyway/Liquibase. Disabled when {@code
 * schema-validation=none}.
 *
 * <p>Each probe names the columns the latest migrations added rather than selecting a literal:
 * "table exists" is not the same as "schema is current", and a partially-applied migration is the
 * more likely failure once a component has more than one. Missing {@code replayed_at}, for
 * instance, would leave parked-input replay failing on a background thread every poll instead of at
 * boot.
 */
@DependsOnDatabaseInitialization
public final class JdbcProcessSchemaValidator implements InitializingBean {

  private static final String[][] PROBES = {
    {"aipersimmon_process_instance", "tenant_id"},
    {"aipersimmon_process_transition", "tenant_id, replayed_at"},
    {"aipersimmon_process_effect", "tenant_id"},
    {"aipersimmon_process_deadline", "tenant_id"},
  };

  private final JdbcTemplate jdbc;

  public JdbcProcessSchemaValidator(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void afterPropertiesSet() {
    for (String[] probe : PROBES) {
      String table = probe[0];
      try {
        jdbc.execute("SELECT " + probe[1] + " FROM " + table + " WHERE 1 = 0");
      } catch (RuntimeException missing) {
        throw new IllegalStateException(
            "process-manager table '"
                + table
                + "' is missing, unreadable, or lacks the columns ("
                + probe[1]
                + ") of a later migration; apply the schema "
                + "(see aipersimmon/db/migration/process-manager) via the aipersimmon-ddd-flyway-spring-boot-starter "
                + "starter or your own Flyway/Liquibase, or set "
                + "aipersimmon.ddd.process-manager.jdbc.schema-validation=none",
            missing);
      }
    }
  }
}
