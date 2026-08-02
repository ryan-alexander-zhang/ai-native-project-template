package com.aipersimmon.ddd.inbox.jdbc;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Verifies at startup that the inbox table exists, so a missing migration fails fast with a clear
 * message instead of at the first consumed message. Without this probe a forgotten {@code
 * aipersimmon.ddd.flyway.components} entry surfaces only when the first delivery arrives — on a
 * listener thread, as a failed dedup check, far from the configuration list that caused it. It
 * never creates tables — the DDL ships as a sample and is applied via Flyway/Liquibase. Disabled
 * when {@code schema-validation=none}. The shape mirrors {@code JdbcProcessSchemaValidator}.
 *
 * <p>The probe names a column a later migration added rather than selecting a literal: "table
 * exists" is not the same as "schema is current" once a component has more than one migration.
 */
@DependsOnDatabaseInitialization
public final class JdbcInboxSchemaValidator implements InitializingBean {

  private static final String PROBE_COLUMNS = "tenant_id";

  private final JdbcTemplate jdbc;

  public JdbcInboxSchemaValidator(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void afterPropertiesSet() {
    try {
      jdbc.execute("SELECT " + PROBE_COLUMNS + " FROM aipersimmon_inbox WHERE 1 = 0");
    } catch (RuntimeException missing) {
      throw new IllegalStateException(
          "inbox table 'aipersimmon_inbox' is missing, unreadable, or lacks the columns ("
              + PROBE_COLUMNS
              + ") of a later migration. Without it every consumed message would fail at the dedup"
              + " check, on a listener thread. Add 'inbox' to aipersimmon.ddd.flyway.components, or"
              + " apply the schema (see aipersimmon/db/migration/inbox) with your own"
              + " Flyway/Liquibase, or set aipersimmon.ddd.inbox.schema-validation=none",
          missing);
    }
  }
}
