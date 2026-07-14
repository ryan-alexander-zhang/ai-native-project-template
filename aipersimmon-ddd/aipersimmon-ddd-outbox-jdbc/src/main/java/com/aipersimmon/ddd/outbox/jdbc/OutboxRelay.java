package com.aipersimmon.ddd.outbox.jdbc;

import com.aipersimmon.ddd.outbox.OutboxDispatcher;
import com.aipersimmon.ddd.outbox.OutboxMessage;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Polls the outbox for unsent rows and dispatches them, marking each sent on
 * success. A failed dispatch leaves the row unsent (its attempt count is bumped)
 * so it is retried on the next poll — at-least-once delivery. Each row is marked
 * on its own, so one failure does not undo already-dispatched rows.
 *
 * <p>Rows are dispatched oldest-first ({@code created_at}, then the identity column
 * as a tiebreaker), so an aggregate's events are delivered in the order they were
 * written. To keep that order under failure, a failed dispatch also holds back the
 * rest of that aggregate's ({@code subject}'s) events for the current poll instead
 * of letting a later event overtake the stuck one.
 *
 * <p>A row that keeps failing is retried until its attempt count reaches
 * {@code max-attempts}; after that the poll no longer selects it (it is a dead
 * letter: it stays in the table for inspection but no longer blocks its aggregate or
 * floods the log). Because such a row is skipped, its aggregate's later events then
 * proceed — order is preserved only up to the point a message is given up on.
 *
 * <p>The poll is guarded by ShedLock ({@code @SchedulerLock}), so across a
 * multi-instance deployment only one instance runs a given poll at a time; the
 * others skip it rather than re-selecting and re-dispatching the same unsent rows.
 */
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private static final String SELECT_UNSENT =
            "SELECT event_id, source, type, version, payload, occurred_at, subject, "
            + "correlation_id, causation_id, trace_id, attempts "
            + "FROM aipersimmon_outbox WHERE sent = FALSE AND attempts < ? "
            + "ORDER BY created_at ASC, id ASC LIMIT ?";
    private static final String MARK_SENT =
            "UPDATE aipersimmon_outbox SET sent = TRUE, sent_at = ? WHERE event_id = ?";
    private static final String BUMP_ATTEMPTS =
            "UPDATE aipersimmon_outbox SET attempts = attempts + 1 WHERE event_id = ?";

    private final JdbcTemplate jdbc;
    private final OutboxDispatcher dispatcher;
    private final Clock clock;
    private final int batchSize;
    private final int maxAttempts;

    public OutboxRelay(JdbcTemplate jdbc, OutboxDispatcher dispatcher, Clock clock,
                       int batchSize, int maxAttempts) {
        this.jdbc = jdbc;
        this.dispatcher = dispatcher;
        this.clock = clock;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
    }

    @Scheduled(fixedDelayString = "${aipersimmon.ddd.outbox.poll-delay-ms:1000}")
    @SchedulerLock(
            name = "${aipersimmon.ddd.outbox.relay.lock-name:${spring.application.name:aipersimmon}-outbox-relay}",
            lockAtMostFor = "${aipersimmon.ddd.outbox.relay.lock-at-most-for:PT10M}")
    public void relay() {
        List<Pending> batch = jdbc.query(SELECT_UNSENT, this::mapRow, maxAttempts, batchSize);
        Set<String> blockedSubjects = new HashSet<>();
        for (Pending pending : batch) {
            OutboxMessage message = pending.message();
            String subject = message.subject();
            if (subject != null && blockedSubjects.contains(subject)) {
                // An earlier event for this aggregate failed this round; hold its
                // later events back so they are not delivered out of order.
                continue;
            }
            try {
                dispatcher.dispatch(message);
                jdbc.update(MARK_SENT, Timestamp.from(clock.instant()), message.eventId());
            } catch (RuntimeException e) {
                jdbc.update(BUMP_ATTEMPTS, message.eventId());
                if (subject != null) {
                    blockedSubjects.add(subject);
                }
                if (pending.attempts() + 1 >= maxAttempts) {
                    log.error("outbox dispatch for eventId={} failed {} times; giving up. The row "
                            + "stays unsent as a dead letter and the relay will no longer select it.",
                            message.eventId(), maxAttempts, e);
                } else {
                    log.warn("outbox dispatch failed for eventId={}, will retry", message.eventId(), e);
                }
            }
        }
    }

    private Pending mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        OutboxMessage message = new OutboxMessage(
                rs.getString("event_id"),
                rs.getString("source"),
                rs.getString("type"),
                rs.getInt("version"),
                rs.getString("payload"),
                rs.getTimestamp("occurred_at").toInstant(),
                rs.getString("subject"),
                rs.getString("correlation_id"),
                rs.getString("causation_id"),
                rs.getString("trace_id"));
        return new Pending(message, rs.getInt("attempts"));
    }

    /** A selected outbox row: the dispatcher-facing message plus its current attempt count. */
    private record Pending(OutboxMessage message, int attempts) {
    }
}
