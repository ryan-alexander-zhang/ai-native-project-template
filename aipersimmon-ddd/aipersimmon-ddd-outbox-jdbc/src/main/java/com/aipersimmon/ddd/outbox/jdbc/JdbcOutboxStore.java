package com.aipersimmon.ddd.outbox.jdbc;

import com.aipersimmon.ddd.outbox.OutboxMessage;
import com.aipersimmon.ddd.outbox.engine.store.OutboxInsert;
import com.aipersimmon.ddd.outbox.engine.store.OutboxLease;
import com.aipersimmon.ddd.outbox.engine.store.OutboxStore;
import com.aipersimmon.ddd.outbox.engine.store.PendingMessage;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
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
          + "tenant_id, correlation_id, causation_id, destination, traceparent, trace_state, sent, "
          + "attempts, created_at) "
          + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

  /**
   * The claimable rows: unsent, not given up on, due, unleased, and the head of their aggregate's
   * live queue. Only the head is admitted, so an aggregate has at most one row claimed anywhere and
   * ordering survives any number of concurrent pollers. An earlier row that is dead-lettered has
   * left the table, and one that has exhausted its attempts is not live — neither blocks. A null or
   * blank subject carries no ordering key, so it never blocks or is blocked.
   */
  private static final String SELECT_CLAIMABLE =
      "SELECT o.event_id FROM aipersimmon_outbox o "
          + "WHERE o.sent = FALSE AND o.attempts < ? "
          + "AND (o.next_attempt_at IS NULL OR o.next_attempt_at <= ?) "
          + "AND (o.lease_until IS NULL OR o.lease_until <= ?) "
          + "AND (o.subject IS NULL OR o.subject = '' OR NOT EXISTS ("
          + "SELECT 1 FROM aipersimmon_outbox older WHERE older.subject = o.subject "
          + "AND older.sent = FALSE AND older.attempts < ? "
          + "AND (older.created_at < o.created_at "
          + "OR (older.created_at = o.created_at AND older.id < o.id)))) "
          + "ORDER BY o.created_at ASC, o.id ASC LIMIT ?";

  private static final String SELECT_LEASED =
      "SELECT o.event_id, o.source, o.type, o.version, o.payload, o.occurred_at, o.subject, "
          + "o.tenant_id, o.correlation_id, o.causation_id, o.destination, o.traceparent, "
          + "o.trace_state, o.attempts "
          + "FROM aipersimmon_outbox o WHERE o.lease_token = ? "
          + "ORDER BY o.created_at ASC, o.id ASC";
  private static final String RELEASE =
      "UPDATE aipersimmon_outbox "
          + "SET lease_owner = NULL, lease_token = NULL, lease_until = NULL WHERE event_id IN (";
  private static final String MARK_SENT =
      "UPDATE aipersimmon_outbox SET sent = TRUE, sent_at = ?, "
          + "lease_owner = NULL, lease_token = NULL, lease_until = NULL WHERE event_id = ?";
  private static final String SCHEDULE_RETRY =
      "UPDATE aipersimmon_outbox SET attempts = attempts + 1, next_attempt_at = ?, "
          + "lease_owner = NULL, lease_token = NULL, lease_until = NULL WHERE event_id = ?";
  private static final String SCHEDULE_BACKOFF =
      "UPDATE aipersimmon_outbox SET next_attempt_at = ?, "
          + "lease_owner = NULL, lease_token = NULL, lease_until = NULL WHERE event_id = ?";
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
        row.destination(),
        row.traceparent(),
        row.traceState(),
        false,
        0,
        Timestamp.from(row.createdAt()));
  }

  @Override
  public List<PendingMessage> claimDue(
      Instant now, int maxAttempts, int batchSize, OutboxLease lease) {
    Timestamp at = Timestamp.from(now);
    List<String> candidates =
        jdbc.queryForList(
            SELECT_CLAIMABLE, String.class, maxAttempts, at, at, maxAttempts, batchSize);
    if (candidates.isEmpty()) {
      return List.of();
    }
    // Stamping the lease is the claim, and it re-checks due-and-unleased so that two instances
    // whose candidate lists overlap cannot both win a row: the loser's update matches nothing.
    // It deliberately does not re-check the head-of-aggregate clause — a row that was the head
    // stays the head, because earlier rows only ever leave the live set.
    List<Object> args = new ArrayList<>();
    args.add(lease.owner());
    args.add(lease.token());
    args.add(Timestamp.from(lease.until()));
    args.addAll(candidates);
    args.add(maxAttempts);
    args.add(at);
    args.add(at);
    jdbc.update(
        "UPDATE aipersimmon_outbox SET lease_owner = ?, lease_token = ?, lease_until = ? "
            + "WHERE event_id IN ("
            + placeholders(candidates.size())
            + ") AND sent = FALSE AND attempts < ? "
            + "AND (next_attempt_at IS NULL OR next_attempt_at <= ?) "
            + "AND (lease_until IS NULL OR lease_until <= ?)",
        args.toArray());
    // Which rows this claim actually won is answered by the database, not by an update count.
    return jdbc.query(SELECT_LEASED, JdbcOutboxStore::mapRow, lease.token());
  }

  @Override
  public void release(List<String> eventIds) {
    if (eventIds.isEmpty()) {
      return;
    }
    jdbc.update(RELEASE + placeholders(eventIds.size()) + ")", eventIds.toArray());
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

  private static String placeholders(int count) {
    return String.join(",", Collections.nCopies(count, "?"));
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
            rs.getString("causation_id"),
            rs.getString("destination"));
    return new PendingMessage(
        message, rs.getInt("attempts"), rs.getString("traceparent"), rs.getString("trace_state"));
  }
}
