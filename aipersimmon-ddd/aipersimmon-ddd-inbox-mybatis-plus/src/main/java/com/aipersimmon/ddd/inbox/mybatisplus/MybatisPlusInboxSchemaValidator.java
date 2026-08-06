package com.aipersimmon.ddd.inbox.mybatisplus;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;

/**
 * Verifies at startup that the inbox table exists, so a missing migration fails fast with a clear
 * message instead of at the first consumed message. Without this probe a forgotten {@code
 * aipersimmon.ddd.flyway.components} entry surfaces only when the first delivery arrives — on a
 * listener thread, as a failed dedup check, far from the configuration list that caused it. Never
 * creates tables. Disabled when {@code schema-validation=none}. The MyBatis-Plus sibling of {@code
 */
@DependsOnDatabaseInitialization
public final class MybatisPlusInboxSchemaValidator implements InitializingBean {

  private final InboxSchemaMapper mapper;

  public MybatisPlusInboxSchemaValidator(InboxSchemaMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public void afterPropertiesSet() {
    try {
      mapper.probe();
    } catch (RuntimeException missing) {
      throw new IllegalStateException(
          "inbox table 'aipersimmon_inbox' is missing, unreadable, or lacks a column added by a"
              + " later migration. Without it every consumed message would fail at the dedup"
              + " check, on a listener thread. Add 'inbox' to aipersimmon.ddd.flyway.components,"
              + " or apply the schema (see aipersimmon/db/migration/inbox) with your own"
              + " Flyway/Liquibase, or set aipersimmon.ddd.inbox.schema-validation=none",
          missing);
    }
  }
}
