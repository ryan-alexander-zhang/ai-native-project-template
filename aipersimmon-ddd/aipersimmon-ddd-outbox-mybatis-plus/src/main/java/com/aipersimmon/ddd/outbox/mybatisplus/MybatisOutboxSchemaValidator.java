package com.aipersimmon.ddd.outbox.mybatisplus;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;

/**
 * Verifies at startup that the outbox tables exist and are current, so a missing or stale migration
 * fails fast with a clear message instead of at the first write. The blast radius this buys down is
 * unusually wide: the outbox insert happens inside the business transaction, so without this probe
 * a forgotten {@code aipersimmon.ddd.flyway.components} entry rolls back <em>every</em> command
 * that publishes an {@code @Externalized} event — far from the configuration list that caused it.
 * Never creates tables. Disabled when {@code schema-validation=none}. The MyBatis-Plus sibling of
 * {@code JdbcOutboxSchemaValidator}.
 *
 * <p>{@code shedlock} is probed too, even though only the retention purge — an opt-in — actually
 * takes a lease on it: the shipped outbox migration always provisions the table, so a migrated
 * schema passes regardless, and a hand-applied schema that left it out fails here at boot instead
 * of on the purge's background thread the day cleanup is switched on. A deployment that
 * deliberately has no {@code shedlock} table because it locks elsewhere (a custom {@code
 * LockProvider}) manages its own schema by definition, and can set {@code schema-validation=none}.
 */
@DependsOnDatabaseInitialization
public final class MybatisOutboxSchemaValidator implements InitializingBean {

  private final OutboxSchemaMapper mapper;

  public MybatisOutboxSchemaValidator(OutboxSchemaMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public void afterPropertiesSet() {
    Map<String, Runnable> probes = new LinkedHashMap<>();
    probes.put("aipersimmon_outbox", mapper::probeOutbox);
    probes.put("aipersimmon_dead_letter", mapper::probeDeadLetter);
    probes.put("shedlock", mapper::probeShedlock);
    probes.forEach(
        (table, probe) -> {
          try {
            probe.run();
          } catch (RuntimeException missing) {
            throw new IllegalStateException(
                "outbox table '"
                    + table
                    + "' is missing, unreadable, or lacks a column added by a later migration."
                    + " The outbox insert runs inside the business transaction, so an absent"
                    + " schema would roll back every command that publishes an @Externalized"
                    + " event. Add 'outbox' to aipersimmon.ddd.flyway.components, or apply the"
                    + " schema (see aipersimmon/db/migration/outbox) with your own"
                    + " Flyway/Liquibase, or set aipersimmon.ddd.outbox.schema-validation=none",
                missing);
          }
        });
  }
}
