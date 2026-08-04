package com.example.samples.s22.ordering;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Reads and writes the framework's own tables from a test, which is a thing to do sparingly and with
 * a reason. Two reasons here.
 *
 * <p><strong>Reading</strong> is how a claim about a give-up is checked at all. The operations
 * endpoint is the surface under test in one class and the instrument in another, and an assertion that
 * only ever consults the surface cannot tell "the message was set aside" from "the endpoint says it
 * was".
 *
 * <p><strong>Writing</strong> appears exactly once, in {@link #writeLeftoverRow}, and it is not a
 * shortcut: it is the only honest way to reproduce a permanent failure. A permanent give-up means the
 * relay has a row whose {@code (type, version)} no local class answers — which by definition cannot
 * be produced by code that is currently deployed. The real cause is a deploy that retired an event
 * class while unsent rows for it were still in the table, and a row written directly is exactly what
 * that leaves behind. Stubbing the dispatcher to throw would have tested the classifier instead.
 */
final class Outbox {

  private Outbox() {}

  /**
   * Inserts an unsent outbox row for an event type this build has no class for — a leftover from a
   * deploy that retired it.
   *
   * <p>{@code destination} is deliberately left null, which means in-process. That is not a detail: an
   * externalized leftover would still be <em>shipped</em>, because the broker does not need our class
   * to accept the bytes, so the poison would surface at the consumer instead (which is what the other
   * module's {@code .DLT} is for). A publisher's permanent failures are the ones it must decode itself
   * on the way out, and reconstructing the event for an in-process listener is the only step on the
   * publishing side that can be permanently impossible.
   */
  static void writeLeftoverRow(JdbcTemplate jdbc, String eventId, String subject) {
    jdbc.update(
        """
        INSERT INTO aipersimmon_outbox
          (event_id, source, type, version, payload, occurred_at, subject,
           correlation_id, sent, attempts, created_at, destination)
        VALUES (?, '/ordering', 'com.example.samples.ordering.OrderRetired', 1,
                '{"orderId":"gone"}', ?, ?, ?, FALSE, 0, ?, NULL)
        """,
        eventId,
        Timestamp.from(Instant.now()),
        subject,
        "corr-" + eventId,
        Timestamp.from(Instant.now()));
  }

  static long liveCount(JdbcTemplate jdbc) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM aipersimmon_outbox", Long.class);
  }

  static long unsentCount(JdbcTemplate jdbc) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM aipersimmon_outbox WHERE sent = FALSE", Long.class);
  }

  static long deadCount(JdbcTemplate jdbc) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM aipersimmon_dead_letter", Long.class);
  }

  static List<Map<String, Object>> deadRows(JdbcTemplate jdbc) {
    return jdbc.queryForList(
        "SELECT event_id, type, subject, attempts, reason, last_error, destination"
            + " FROM aipersimmon_dead_letter ORDER BY failed_at, id");
  }

  static void clear(JdbcTemplate jdbc) {
    jdbc.update("DELETE FROM aipersimmon_dead_letter");
    jdbc.update("DELETE FROM aipersimmon_outbox");
    jdbc.update("DELETE FROM s22_order");
  }
}
