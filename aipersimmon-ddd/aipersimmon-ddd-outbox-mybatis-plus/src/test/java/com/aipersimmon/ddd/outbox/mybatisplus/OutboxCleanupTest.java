package com.aipersimmon.ddd.outbox.mybatisplus;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.aipersimmon.ddd.outbox.engine.cleanup.OutboxCleanup;
import com.aipersimmon.ddd.outbox.engine.store.OutboxStore;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Verifies the opt-in retention cleanup deletes only sent rows past the window and keeps recent
 * sent rows and all unsent rows (including dead letters).
 */
@SpringBootTest(
    classes = OutboxCleanupTest.TestApp.class,
    properties = {
      "aipersimmon.ddd.outbox.relay.enabled=false",
      "aipersimmon.ddd.outbox.cleanup.enabled=true",
      "aipersimmon.ddd.outbox.cleanup.retention-seconds=1"
    })
class OutboxCleanupTest {

  @SpringBootConfiguration
  @EnableAutoConfiguration
  static class TestApp {}

  @Autowired JdbcTemplate jdbc;
  @Autowired OutboxStore outboxStore;

  /**
   * The purge under test is invoked directly rather than through the autowired bean.
   *
   * <p>{@code OutboxCleanup.purge} carries {@code @SchedulerLock}, and
   * {@code @Scheduled(fixedDelay)} fires once as soon as the context is up — so a call through the
   * proxy races that first scheduled run for the lock, and ShedLock's answer to losing is to skip
   * the method silently. The test then asserted on a purge that never ran, and failed
   * intermittently (issue-00100). Constructing the collaborator removes the lock from a question
   * that is about which rows the DELETE matches. A concurrent scheduled purge cannot change the
   * outcome either way: it can only delete the same expired row this test expects to be gone.
   */
  private OutboxCleanup cleanup() {
    return new OutboxCleanup(outboxStore, Clock.systemUTC(), 1, 500);
  }

  @BeforeEach
  void reset() {
    jdbc.update("DELETE FROM aipersimmon_outbox");
  }

  private void insert(String eventId, boolean sent, Instant sentAt) {
    jdbc.update(
        "INSERT INTO aipersimmon_outbox (event_id, source, type, version, payload, occurred_at, "
            + "subject, correlation_id, causation_id, sent, attempts, created_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        eventId,
        "test",
        "SampleEvent",
        1,
        "{}",
        Timestamp.from(Instant.now()),
        null,
        "corr",
        null,
        sent,
        0,
        Timestamp.from(Instant.now()));
    if (sentAt != null) {
      jdbc.update(
          "UPDATE aipersimmon_outbox SET sent_at = ? WHERE event_id = ?",
          Timestamp.from(sentAt),
          eventId);
    }
  }

  private Integer count(String eventId) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM aipersimmon_outbox WHERE event_id = ?", Integer.class, eventId);
  }

  @Test
  void removesSentRowsPastRetentionButKeepsRecentAndUnsent() {
    insert("old-sent", true, Instant.now().minusSeconds(3600));
    insert("recent-sent", true, Instant.now());
    insert("unsent", false, null);

    cleanup().purge();

    assertEquals(Integer.valueOf(0), count("old-sent"), "a sent row past retention is removed");
    assertEquals(Integer.valueOf(1), count("recent-sent"), "a recently sent row is kept");
    assertEquals(Integer.valueOf(1), count("unsent"), "an unsent row is never removed");
  }
}
