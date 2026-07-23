package com.aipersimmon.ddd.processmanager.mybatisplus.autoconfigure;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;

/**
 * Verifies at startup that the four process tables exist, so a missing migration fails fast with a
 * clear message instead of at the first advance. It never creates tables — the DDL ships as a
 * sample (see {@code aipersimmon/db/migration/process-manager}) and is applied via
 * Flyway/Liquibase. Disabled when {@code schema-validation=none}. The MyBatis-Plus sibling of
 * {@code JdbcProcessSchemaValidator}.
 */
@DependsOnDatabaseInitialization
public final class MybatisProcessSchemaValidator implements InitializingBean {

  private final ProcessSchemaMapper mapper;

  public MybatisProcessSchemaValidator(ProcessSchemaMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public void afterPropertiesSet() {
    Map<String, Runnable> probes = new LinkedHashMap<>();
    probes.put("aipersimmon_process_instance", mapper::probeInstance);
    probes.put("aipersimmon_process_transition", mapper::probeTransition);
    probes.put("aipersimmon_process_effect", mapper::probeEffect);
    probes.put("aipersimmon_process_deadline", mapper::probeDeadline);
    probes.forEach(
        (table, probe) -> {
          try {
            probe.run();
          } catch (RuntimeException missing) {
            throw new IllegalStateException(
                "process-manager table '"
                    + table
                    + "' is missing or unreadable; apply the schema "
                    + "(see aipersimmon/db/migration/process-manager) via the "
                    + "aipersimmon-ddd-flyway starter or your own Flyway/Liquibase, or set "
                    + "aipersimmon.ddd.process-manager.schema-validation=none",
                missing);
          }
        });
  }
}
