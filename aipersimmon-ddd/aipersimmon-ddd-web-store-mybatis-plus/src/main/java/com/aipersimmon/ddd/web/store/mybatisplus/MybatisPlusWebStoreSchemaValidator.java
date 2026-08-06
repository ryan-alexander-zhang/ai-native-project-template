package com.aipersimmon.ddd.web.store.mybatisplus;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;

/**
 * Verifies at startup that the three web-store tables exist and are current, so a missing or stale
 * migration fails fast with a clear message instead of at the first covered request. Without this
 * probe a forgotten {@code aipersimmon.ddd.flyway.components} entry surfaces as a 500 at the edge —
 * the idempotency, replay-protection and rate-limit filters read these tables before the request
 * reaches any handler — far from the configuration list that caused it. It never creates tables;
 * the DDL ships as Flyway migrations in {@code aipersimmon-ddd-web}. Disabled when {@code
 * schema-validation=none}.
 *
 * <p>Each probe names columns the later migrations added rather than selecting a literal: "table
 * exists" is not the same as "schema is current" once a component has more than one migration.
 */
@DependsOnDatabaseInitialization
public final class MybatisPlusWebStoreSchemaValidator implements InitializingBean {

  private final WebStoreSchemaMapper mapper;

  public MybatisPlusWebStoreSchemaValidator(WebStoreSchemaMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public void afterPropertiesSet() {
    probe(
        "aipersimmon_web_idempotency",
        "tenant_id, principal, fingerprint, state",
        mapper::probeIdempotency);
    probe("aipersimmon_web_nonce", "tenant_id", mapper::probeNonce);
    probe("aipersimmon_web_rate_limit", "tenant_id", mapper::probeRateLimit);
  }

  private void probe(String table, String columns, Supplier<List<Map<String, Object>>> probe) {
    try {
      probe.get();
    } catch (RuntimeException missing) {
      throw new IllegalStateException(
          "web-store table '"
              + table
              + "' is missing, unreadable, or lacks the columns ("
              + columns
              + ") of a later migration. Without it every request covered by idempotency, replay"
              + " protection or rate limiting would fail at the edge. Add 'web-store' to"
              + " aipersimmon.ddd.flyway.components, or apply the schema (see"
              + " aipersimmon/db/migration/web-store) with your own Flyway/Liquibase, or set"
              + " aipersimmon.ddd.web.store.schema-validation=none",
          missing);
    }
  }
}
