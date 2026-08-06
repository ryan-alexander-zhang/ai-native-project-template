package com.aipersimmon.ddd.outbox.mybatisplus;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.aipersimmon.ddd.outbox.OutboxDispatcher;
import com.aipersimmon.ddd.outbox.OutboxMessage;
import com.aipersimmon.ddd.outbox.engine.relay.OutboxRelay;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The mark-sent-failure contract: a dispatch that reached the broker but whose {@code sent=TRUE}
 * update then failed must not be dead-lettered or counted against the retry budget — it is
 * re-dispatched (an accepted at-least-once duplicate), which the consumer's inbox dedups. The
 * mapper issues the mark-sent through the SqlSessionFactory (not the JdbcTemplate), so the failure
 * is forced with an H2 trigger that rejects the update which sets {@code sent = TRUE} — and only
 * that one, so the relay's claim on the row and the release that follows still work.
 *
 * <p>{@code max-attempts=1} makes the sharp edge explicit: before the fix a single mark-sent
 * failure exhausts the budget and dead-letters a message that was already delivered.
 */
@SpringBootTest(
    classes = OutboxRelayMarkSentFailureTest.TestApp.class,
    properties = {
      "aipersimmon.ddd.outbox.relay.enabled=false",
      "aipersimmon.ddd.outbox.max-attempts=1"
    })
class OutboxRelayMarkSentFailureTest {

  @SpringBootConfiguration
  @EnableAutoConfiguration
  static class TestApp {

    @Bean
    CountingDispatcher dispatcher() {
      return new CountingDispatcher();
    }
  }

  /** Records the message as delivered (the broker ack'd); every dispatch succeeds. */
  static class CountingDispatcher implements OutboxDispatcher {
    final AtomicInteger dispatchCount = new AtomicInteger();

    @Override
    public void dispatch(OutboxMessage message) {
      dispatchCount.incrementAndGet();
    }
  }

  @Autowired OutboxRelay relay;
  @Autowired JdbcTemplate jdbc;
  @Autowired CountingDispatcher dispatcher;

  @BeforeEach
  void reset() {
    jdbc.execute("DROP TRIGGER IF EXISTS trg_block_outbox_update");
    jdbc.update("DELETE FROM aipersimmon_outbox");
    jdbc.update("DELETE FROM aipersimmon_dead_letter");
    dispatcher.dispatchCount.set(0);
  }

  private void insert(String eventId) {
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
        false,
        0,
        Timestamp.from(Instant.now()));
  }

  @Test
  void aMarkSentFailureAfterASuccessfulDispatchIsNotTreatedAsADispatchFailure() {
    insert("e1");
    // Reject the mark-sent specifically. The relay also updates the row to claim it and to hand it
    // back, and blocking those would test something else entirely.
    jdbc.execute(
        "CREATE TRIGGER trg_block_outbox_update BEFORE UPDATE ON aipersimmon_outbox "
            + "FOR EACH ROW CALL \""
            + BlockMarkSentTrigger.class.getName()
            + "\"");

    assertDoesNotThrow(relay::relay);
    assertEquals(1, dispatcher.dispatchCount.get(), "the message was delivered to the broker");

    assertEquals(
        0,
        jdbc.queryForObject("SELECT COUNT(*) FROM aipersimmon_dead_letter", Integer.class),
        "a delivered message must not be dead-lettered because mark-sent failed");
    assertEquals(
        1,
        jdbc.queryForObject("SELECT COUNT(*) FROM aipersimmon_outbox", Integer.class),
        "the row stays in the outbox (it could not be marked sent)");
    assertFalse(
        Boolean.TRUE.equals(
            jdbc.queryForObject(
                "SELECT sent FROM aipersimmon_outbox WHERE event_id = ?", Boolean.class, "e1")),
        "the row remains unsent because the mark-sent update failed");
    assertEquals(
        Integer.valueOf(0),
        jdbc.queryForObject(
            "SELECT attempts FROM aipersimmon_outbox WHERE event_id = ?", Integer.class, "e1"),
        "a mark-sent failure is not a dispatch attempt");
    assertNull(
        jdbc.queryForObject(
            "SELECT next_attempt_at FROM aipersimmon_outbox WHERE event_id = ?",
            Timestamp.class,
            "e1"),
        "it is not scheduled as a failed-dispatch retry; the next poll re-dispatches it");

    // The next poll re-delivers it (the accepted at-least-once duplicate) rather than losing it.
    relay.relay();
    assertEquals(
        2,
        dispatcher.dispatchCount.get(),
        "the unsent row is re-dispatched on the next poll (an at-least-once duplicate)");
  }

  /**
   * H2 trigger: reject the update that marks a row sent, and nothing else. The column position of
   * {@code sent} is looked up once rather than hard-coded, so adding a column cannot quietly turn
   * this into a trigger that blocks the wrong statement.
   */
  public static class BlockMarkSentTrigger implements org.h2.api.Trigger {

    private int sentColumn = -1;

    @Override
    public void init(
        Connection conn,
        String schemaName,
        String triggerName,
        String tableName,
        boolean before,
        int type)
        throws SQLException {
      try (ResultSet columns = conn.getMetaData().getColumns(null, schemaName, tableName, "SENT")) {
        if (columns.next()) {
          sentColumn = columns.getInt("ORDINAL_POSITION") - 1;
        }
      }
    }

    @Override
    public void fire(Connection conn, Object[] oldRow, Object[] newRow) {
      if (sentColumn < 0) {
        throw new IllegalStateException("the outbox table has no sent column to watch");
      }
      if (newRow != null && Boolean.TRUE.equals(newRow[sentColumn])) {
        throw new RuntimeException("simulated failure marking the row sent");
      }
    }
  }
}
