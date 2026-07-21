package com.aipersimmon.ddd.saga.spring;

import com.aipersimmon.ddd.saga.Deadline;
import com.aipersimmon.ddd.saga.DeadlineHandler;
import com.aipersimmon.ddd.saga.DeadlineScheduler;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Durable {@link DeadlineScheduler} that stores pending deadlines in a database table and fires
 * them from a scheduled poll — the same reliable "persist a row, poll it, act, delete it" mechanism
 * the transactional outbox uses, applied to timeouts instead of messages. Unlike the in-process
 * scheduler, pending deadlines survive a restart (they are rows, not heap timers) and can be picked
 * up by any instance polling the table.
 *
 * <p>{@link #schedule} inserts (or reschedules) a row keyed by correlation id and name; {@link
 * #cancel} deletes it. The {@link #poll()} method, run on a schedule, loads every row whose fire
 * time has passed, dispatches it to the {@link DeadlineHandler}, and deletes it on success; a row
 * whose handler throws is left in place and retried on the next poll. Delivery is therefore
 * at-least-once — firing may be delayed by up to the poll interval, and (across instances) a
 * deadline may fire more than once — so the handler must be idempotent, which a saga's guarded
 * lifecycle already provides (a deadline for an already-ended saga is a no-op).
 */
public class JdbcDeadlineScheduler implements DeadlineScheduler {

  private static final Logger log = LoggerFactory.getLogger(JdbcDeadlineScheduler.class);

  private static final String INSERT =
      "INSERT INTO aipersimmon_deadline (correlation_id, name, fire_at) VALUES (?, ?, ?)";
  private static final String RESCHEDULE =
      "UPDATE aipersimmon_deadline SET fire_at = ? WHERE correlation_id = ? AND name = ?";
  private static final String DELETE =
      "DELETE FROM aipersimmon_deadline WHERE correlation_id = ? AND name = ?";
  private static final String SELECT_DUE =
      "SELECT correlation_id, name, fire_at FROM aipersimmon_deadline "
          + "WHERE fire_at <= ? ORDER BY fire_at ASC LIMIT ?";

  private final JdbcTemplate jdbc;
  private final Supplier<DeadlineHandler> handler;
  private final Clock clock;
  private final int batchSize;

  /**
   * @param handler resolves the {@link DeadlineHandler} lazily (at fire time), so a process manager
   *     can arm deadlines through this scheduler while also being the handler, without a
   *     construction-time cycle
   */
  public JdbcDeadlineScheduler(
      JdbcTemplate jdbc, Supplier<DeadlineHandler> handler, Clock clock, int batchSize) {
    this.jdbc = jdbc;
    this.handler = handler;
    this.clock = clock;
    this.batchSize = batchSize;
  }

  @Override
  public void schedule(Deadline deadline) {
    Timestamp fireAt = Timestamp.from(deadline.fireAt());
    try {
      jdbc.update(INSERT, deadline.correlationId(), deadline.name(), fireAt);
    } catch (DuplicateKeyException alreadyScheduled) {
      jdbc.update(RESCHEDULE, fireAt, deadline.correlationId(), deadline.name());
    }
  }

  @Override
  public void cancel(String correlationId, String name) {
    jdbc.update(DELETE, correlationId, name);
  }

  /** Fire every deadline whose time has passed, then delete it; retry on failure. */
  @Scheduled(fixedDelayString = "${aipersimmon.ddd.saga.deadline.poll-delay-ms:1000}")
  public void poll() {
    List<Deadline> due =
        jdbc.query(
            SELECT_DUE,
            (rs, rowNum) ->
                new Deadline(
                    rs.getString("correlation_id"),
                    rs.getString("name"),
                    rs.getTimestamp("fire_at").toInstant()),
            Timestamp.from(clock.instant()),
            batchSize);
    for (Deadline deadline : due) {
      try {
        handler.get().onDeadline(deadline);
        jdbc.update(DELETE, deadline.correlationId(), deadline.name());
      } catch (RuntimeException e) {
        log.warn(
            "deadline dispatch failed for correlationId={} name={}, will retry",
            deadline.correlationId(),
            deadline.name(),
            e);
      }
    }
  }
}
