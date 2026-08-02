package com.aipersimmon.ddd.operationlog.jdbc;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Verifies at startup that the audit table exists, so a missing migration fails fast with a clear
 * message instead of at the first annotated command. The blast radius this buys down is unusually
 * wide: the success-path append is deliberately fail-closed inside the business transaction (an
 * audit gap must not go unnoticed), so without this probe a forgotten {@code
 * aipersimmon.ddd.flyway.components} entry rolls back <em>every</em> {@code @OperationLog} command
 * — far from the configuration list that caused it. It never creates tables — the DDL ships as a
 * sample and is applied via Flyway/Liquibase. Disabled when {@code schema-validation=none}. The
 * shape mirrors {@code JdbcProcessSchemaValidator}.
 *
 * <p>The probe names columns rather than selecting a literal: "table exists" is not the same as
 * "schema is current" once a component has more than one migration.
 */
@DependsOnDatabaseInitialization
public final class JdbcOperationLogSchemaValidator implements InitializingBean {

  private static final String PROBE_COLUMNS = "record_id, tenant_id, schema_version";

  private final JdbcTemplate jdbc;

  public JdbcOperationLogSchemaValidator(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void afterPropertiesSet() {
    try {
      jdbc.execute("SELECT " + PROBE_COLUMNS + " FROM aipersimmon_operation_log WHERE 1 = 0");
    } catch (RuntimeException missing) {
      throw new IllegalStateException(
          "operation-log table 'aipersimmon_operation_log' is missing, unreadable, or lacks the"
              + " columns ("
              + PROBE_COLUMNS
              + ") of a later migration. Without it every @OperationLog command would roll back at"
              + " the audit append. Add 'operation-log' to aipersimmon.ddd.flyway.components, or"
              + " apply the schema (see aipersimmon/db/migration/operation-log) with your own"
              + " Flyway/Liquibase, or set aipersimmon.ddd.operation-log.schema-validation=none",
          missing);
    }
  }
}
