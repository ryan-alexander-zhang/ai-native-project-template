package com.aipersimmon.ddd.outbox.jdbc;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Verifies at startup that the outbox tables exist and are current, so a missing or stale migration
 * fails fast with a clear message instead of at the first write. The blast radius this buys down is
 * unusually wide: the outbox insert happens inside the business transaction, so without this probe
 * a forgotten {@code aipersimmon.ddd.flyway.components} entry rolls back <em>every</em> command
 * that publishes an {@code @Externalized} event — far from the configuration list that caused it.
 * It never creates tables — the DDL ships as a sample and is applied via Flyway/Liquibase. Disabled
 * when {@code schema-validation=none}. The shape mirrors {@code JdbcProcessSchemaValidator}.
 *
 * <p>Each probe names columns the later migrations added rather than selecting a literal: "table
 * exists" is not the same as "schema is current" once a component has more than one migration.
 *
 * <p>{@code shedlock} is probed too, on its ShedLock contract columns, even though only the
 * retention purge — an opt-in — actually takes a lease on it. The shipped outbox migration always
 * provisions the table, so a migrated schema passes regardless; probing it anyway means a
 * hand-applied schema that left it out fails here at boot, instead of on the purge's background
 * thread the day cleanup is switched on. A deployment that deliberately has no {@code shedlock}
 * table because it locks elsewhere (a custom {@code LockProvider}) manages its own schema by
 * definition, and can set {@code schema-validation=none}.
 */
@DependsOnDatabaseInitialization
public final class JdbcOutboxSchemaValidator implements InitializingBean {

  private static final String[][] PROBES = {
    {"aipersimmon_outbox", "tenant_id, lease_token, destination"},
    {"aipersimmon_dead_letter", "tenant_id, destination"},
    {"shedlock", "name, lock_until"},
  };

  private final JdbcTemplate jdbc;

  public JdbcOutboxSchemaValidator(JdbcTemplate jdbc) {
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
            "outbox table '"
                + table
                + "' is missing, unreadable, or lacks the columns ("
                + probe[1]
                + ") of a later migration. The outbox insert runs inside the business transaction,"
                + " so an absent schema would roll back every command that publishes an"
                + " @Externalized event. Add 'outbox' to aipersimmon.ddd.flyway.components, or"
                + " apply the schema (see aipersimmon/db/migration/outbox) with your own"
                + " Flyway/Liquibase, or set aipersimmon.ddd.outbox.schema-validation=none",
            missing);
      }
    }
  }
}
