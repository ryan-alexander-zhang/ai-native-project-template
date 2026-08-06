package com.aipersimmon.ddd.operationlog.mybatisplus;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/**
 * The opt-in audit retention: only records past the window are removed, and a backlog larger than
 * one page drains by looping id pages in a single run — the first purge of a long-lived audit table
 * must be many small transactions, not one giant DELETE.
 */
class MybatisPlusOperationLogCleanupTest {

  private static final Instant NOW = Instant.parse("2026-08-03T10:00:00Z");
  private static final AtomicInteger DB = new AtomicInteger();

  private final SimpleDriverDataSource dataSource =
      new SimpleDriverDataSource(
          new org.h2.Driver(),
          "jdbc:h2:mem:oplog-cleanup" + DB.incrementAndGet() + ";DB_CLOSE_DELAY=-1",
          "sa",
          "");
  private final JdbcTemplate jdbc = new JdbcTemplate(dataSource);

  MybatisPlusOperationLogCleanupTest() {
    DatabasePopulatorUtils.execute(
        new ResourceDatabasePopulator(
            new ClassPathResource(
                "aipersimmon/db/migration/operation-log/h2/V1__aipersimmon_operation_log.sql")),
        dataSource);
  }

  @AfterEach
  void tearDown() {
    jdbc.execute("SHUTDOWN");
  }

  @Test
  void removesOnlyRecordsPastTheRetentionWindow() {
    insert("old", NOW.minusSeconds(7200));
    insert("recent", NOW.minusSeconds(10));

    new MybatisPlusOperationLogCleanup(mapper(), Clock.fixed(NOW, ZoneOffset.UTC), 3600, 500)
        .purge();

    assertEquals(
        1,
        count(),
        "an audit record inside its retention window is a record someone may still be answerable "
            + "for; only what has outlived the declared window may go");
    assertEquals(
        1,
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM aipersimmon_operation_log WHERE record_id = 'recent'",
            Integer.class));
  }

  @Test
  void aBacklogLargerThanOnePageDrainsInOneRunByLoopingPages() {
    for (int i = 0; i < 3; i++) {
      insert("expired-" + i, NOW.minusSeconds(7200));
    }

    new MybatisPlusOperationLogCleanup(mapper(), Clock.fixed(NOW, ZoneOffset.UTC), 3600, 1).purge();

    assertEquals(0, count(), "batchSize=1 must still drain everything, by paging");
  }

  private OperationLogMapper mapper() {
    return MybatisPlusSinkScenarios.mapper(dataSource);
  }

  private int count() {
    return jdbc.queryForObject("SELECT COUNT(*) FROM aipersimmon_operation_log", Integer.class);
  }

  private void insert(String recordId, Instant recordedAt) {
    jdbc.update(
        "INSERT INTO aipersimmon_operation_log (record_id, source, tenant_id, idempotency_key,"
            + " operation_code, actor_type, target_type, target_id, outcome, completion,"
            + " schema_version, occurred_at, recorded_at)"
            + " VALUES (?, 'svc', '__root__', ?, 'op.code', 'USER', 'Order', 'o1', 'SUCCEEDED',"
            + " 'COMPLETED', 1, ?, ?)",
        recordId,
        String.format("%64s", recordId).replace(' ', '0'),
        Timestamp.from(recordedAt),
        Timestamp.from(recordedAt));
  }
}
