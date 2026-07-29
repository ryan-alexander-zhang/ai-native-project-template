package com.aipersimmon.ddd.outbox.jdbc;

import com.aipersimmon.ddd.outbox.OutboxMessage;
import com.aipersimmon.ddd.outbox.engine.store.OutboxInsert;
import com.aipersimmon.ddd.outbox.engine.store.OutboxStore;
import com.aipersimmon.ddd.outbox.engine.store.PendingMessage;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link OutboxStore} over {@code aipersimmon_outbox} with a plain {@link JdbcTemplate}. SQL only —
 * every decision about ordering, retries and giving up lives in the engine.
 */
public class JdbcOutboxStore implements OutboxStore {

  private static final String INSERT =
      "INSERT INTO aipersimmon_outbox "
          + "(event_id, source, type, version, payload, occurred_at, subject, "
          + "tenant_id, correlation_id, causation_id, traceparent, trace_state, sent, attempts, "
          + "created_at) "
          + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
  private static final String SELECT_DUE =
      "SELECT o.event_id, o.source, o.type, o.version, o.payload, o.occurred_at, o.subject, "
          + "o.tenant_id, o.correlation_id, o.causation_id, o.traceparent, o.trace_state, o.attempts "
          + "FROM aipersimmon_outbox o "
          + "WHERE o.sent = FALSE AND o.attempts < ? "
          + "AND (o.next_attempt_at IS NULL OR o.next_attempt_at <= ?) "
          // Per-aggregate ordering across polls: hold a row back while an EARLIER event
          // of the same subject is still live (sent=false, attempts<max) but not yet due
          // — i.e. backing off — because it cannot be dispatched this poll and a later
          // event must not overtake it. An earlier event that is due is not a blocker
          // (both ride this batch, ordered, and in-batch failure holds the rest); nor is
          // a dead-lettered one (moved out) or a legacy abandoned one (attempts>=max).
          // A null/blank subject has no ordering key, so it never blocks or is blocked.
          + "AND (o.subject IS NULL OR o.subject = '' OR NOT EXISTS ("
          + "SELECT 1 FROM aipersimmon_outbox older WHERE older.subject = o.subject "
          + "AND older.sent = FALSE AND older.attempts < ? "
          + "AND older.next_attempt_at IS NOT NULL AND older.next_attempt_at > ? "
          + "AND (older.created_at < o.created_at "
          + "OR (older.created_at = o.created_at AND older.id < o.id)))) "
          + "ORDER BY o.created_at ASC, o.id ASC LIMIT ?";
  private static final String MARK_SENT =
      "UPDATE aipersimmon_outbox SET sent = TRUE, sent_at = ? WHERE event_id = ?";
  private static final String SCHEDULE_RETRY =
      "UPDATE aipersimmon_outbox SET attempts = attempts + 1, next_attempt_at = ? WHERE event_id = ?";
  private static final String SCHEDULE_BACKOFF =
      "UPDATE aipersimmon_outbox SET next_attempt_at = ? WHERE event_id = ?";
  private static final String DELETE_SENT =
      "DELETE FROM aipersimmon_outbox WHERE sent = TRUE AND sent_at < ?";

  private final JdbcTemplate jdbc;

  public JdbcOutboxStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insert(OutboxInsert row) {
    jdbc.update(
        INSERT,
        row.eventId(),
        row.source(),
        row.type(),
        row.version(),
        row.payload(),
        Timestamp.from(row.occurredAt()),
        row.subject(),
        row.tenantId(),
        row.correlationId(),
        row.causationId(),
        row.traceparent(),
        row.traceState(),
        false,
        0,
        Timestamp.from(row.createdAt()));
  }

  @Override
  public List<PendingMessage> findDue(Instant now, int maxAttempts, int batchSize) {
    Timestamp at = Timestamp.from(now);
    return jdbc.query(
        SELECT_DUE, JdbcOutboxStore::mapRow, maxAttempts, at, maxAttempts, at, batchSize);
  }

  @Override
  public void markSent(String eventId, Instant sentAt) {
    jdbc.update(MARK_SENT, Timestamp.from(sentAt), eventId);
  }

  @Override
  public void scheduleRetry(String eventId, Instant nextAttemptAt) {
    jdbc.update(SCHEDULE_RETRY, Timestamp.from(nextAttemptAt), eventId);
  }

  @Override
  public void backOffWithoutAttempt(String eventId, Instant nextAttemptAt) {
    jdbc.update(SCHEDULE_BACKOFF, Timestamp.from(nextAttemptAt), eventId);
  }

  @Override
  public int deleteSentBefore(Instant sentBefore) {
    return jdbc.update(DELETE_SENT, Timestamp.from(sentBefore));
  }

  private static PendingMessage mapRow(ResultSet rs, int rowNum) throws SQLException {
    OutboxMessage message =
        new OutboxMessage(
            rs.getString("event_id"),
            rs.getString("source"),
            rs.getString("type"),
            rs.getInt("version"),
            rs.getString("payload"),
            rs.getTimestamp("occurred_at").toInstant(),
            rs.getString("subject"),
            rs.getString("tenant_id"),
            rs.getString("correlation_id"),
            rs.getString("causation_id"));
    return new PendingMessage(
        message, rs.getInt("attempts"), rs.getString("traceparent"), rs.getString("trace_state"));
  }
}
