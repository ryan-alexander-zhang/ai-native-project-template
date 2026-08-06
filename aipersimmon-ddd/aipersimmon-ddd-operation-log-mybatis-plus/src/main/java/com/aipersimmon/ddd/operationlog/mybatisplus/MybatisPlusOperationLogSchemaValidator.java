package com.aipersimmon.ddd.operationlog.mybatisplus;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;

/**
 * Verifies at startup that the audit table exists, so a missing migration fails fast with a clear
 * message instead of at the first annotated command. The blast radius this buys down is unusually
 * wide: the success-path append is deliberately fail-closed inside the business transaction, so
 * without this probe a forgotten {@code aipersimmon.ddd.flyway.components} entry rolls back
 * <em>every</em> {@code @OperationLog} command — far from the configuration list that caused it.
 * Never creates tables. Disabled when {@code schema-validation=none}. The MyBatis-Plus sibling of
 */
@DependsOnDatabaseInitialization
public final class MybatisPlusOperationLogSchemaValidator implements InitializingBean {

  private final OperationLogSchemaMapper mapper;

  public MybatisPlusOperationLogSchemaValidator(OperationLogSchemaMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public void afterPropertiesSet() {
    try {
      mapper.probe();
    } catch (RuntimeException missing) {
      throw new IllegalStateException(
          "operation-log table 'aipersimmon_operation_log' is missing, unreadable, or lacks a"
              + " column added by a later migration. Without it every @OperationLog command would"
              + " roll back at the audit append. Add 'operation-log' to"
              + " aipersimmon.ddd.flyway.components, or apply the schema (see"
              + " aipersimmon/db/migration/operation-log) with your own Flyway/Liquibase, or set"
              + " aipersimmon.ddd.operation-log.schema-validation=none",
          missing);
    }
  }
}
