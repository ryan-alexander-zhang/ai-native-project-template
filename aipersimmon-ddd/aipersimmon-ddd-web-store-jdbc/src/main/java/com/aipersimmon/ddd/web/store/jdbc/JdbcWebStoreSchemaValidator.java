package com.aipersimmon.ddd.web.store.jdbc;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Verifies at startup that the three web-store tables exist and are current, so a missing or stale
 * migration fails fast with a clear message instead of at the first covered request. Without this
 * probe a forgotten {@code aipersimmon.ddd.flyway.components} entry surfaces as a 500 at the edge —
 * the idempotency, replay-protection and rate-limit filters read these tables before the request
 * reaches any handler — far from the configuration list that caused it. It never creates tables —
 * the DDL ships as a sample and is applied via Flyway/Liquibase. Disabled when {@code
 * schema-validation=none}. The shape mirrors {@code JdbcProcessSchemaValidator}.
 *
 * <p>Each probe names columns the later migrations added rather than selecting a literal: "table
 * exists" is not the same as "schema is current" once a component has more than one migration.
 */
@DependsOnDatabaseInitialization
public final class JdbcWebStoreSchemaValidator implements InitializingBean {

  private static final String[][] PROBES = {
    {"aipersimmon_web_idempotency", "tenant_id, principal, fingerprint, state"},
    {"aipersimmon_web_nonce", "tenant_id"},
    {"aipersimmon_web_rate_limit", "tenant_id"},
  };

  private final JdbcTemplate jdbc;

  public JdbcWebStoreSchemaValidator(JdbcTemplate jdbc) {
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
            "web-store table '"
                + table
                + "' is missing, unreadable, or lacks the columns ("
                + probe[1]
                + ") of a later migration. Without it every request covered by idempotency, replay"
                + " protection or rate limiting would fail at the edge. Add 'web-store' to"
                + " aipersimmon.ddd.flyway.components, or apply the schema (see"
                + " aipersimmon/db/migration/web-store) with your own Flyway/Liquibase, or set"
                + " aipersimmon.ddd.web.store.schema-validation=none",
            missing);
      }
    }
  }
}
