package com.aipersimmon.ddd.outbox.jdbc;

import com.aipersimmon.ddd.outbox.DeadLetterStore;
import com.aipersimmon.ddd.outbox.FailureClassifier;
import com.aipersimmon.ddd.outbox.OutboxDispatcher;
import com.aipersimmon.ddd.outbox.OutboxMessage;
import com.aipersimmon.ddd.outbox.RetryBackoff;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Polls the outbox for unsent rows that are due and dispatches them, marking each sent
 * on success. Each row is marked on its own, so one failure does not undo already-
 * dispatched rows — at-least-once delivery.
 *
 * <p>Rows are dispatched oldest-first ({@code created_at}, then the identity column as a
 * tiebreaker), so an aggregate's events are delivered in the order they were written. To
 * keep that order under failure, a failed dispatch that is going to be retried also holds
 * back the rest of that aggregate's ({@code subject}'s) events for the current poll,
 * instead of letting a later event overtake the stuck one.
 *
 * <p>A failed dispatch is classified by the {@link FailureClassifier}. A <em>permanent</em>
 * failure is dead-lettered at once (no retries wasted). A <em>transient</em> failure is
 * retried with exponential backoff: its {@code next_attempt_at} is pushed out (see
 * {@link RetryBackoff}) so the poll skips it until then, rather than re-attempting it every
 * second. When a transient failure has burned through {@code max-attempts} it too is
 * dead-lettered. In every give-up case the row is <em>moved</em> to the
 * {@link DeadLetterStore} (out of the outbox), so the hot table holds only live work and a
 * spent message is preserved for inspection and replay rather than lost. Because a dead-
 * lettered row leaves the table, its aggregate's later events then proceed — ordering is
 * preserved only up to the point a message is given up on.
 *
 * <p>The poll is guarded by ShedLock ({@code @SchedulerLock}), so across a multi-instance
 * deployment only one instance runs a given poll at a time; the others skip it rather than
 * re-selecting and re-dispatching the same unsent rows.
 */
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private static final String SELECT_DUE =
            "SELECT event_id, source, type, version, payload, occurred_at, subject, "
            + "correlation_id, causation_id, trace_id, attempts "
            + "FROM aipersimmon_outbox WHERE sent = FALSE AND attempts < ? "
            + "AND (next_attempt_at IS NULL OR next_attempt_at <= ?) "
            + "ORDER BY created_at ASC, id ASC LIMIT ?";
    private static final String MARK_SENT =
            "UPDATE aipersimmon_outbox SET sent = TRUE, sent_at = ? WHERE event_id = ?";
    private static final String SCHEDULE_RETRY =
            "UPDATE aipersimmon_outbox SET attempts = attempts + 1, next_attempt_at = ? WHERE event_id = ?";

    private final JdbcTemplate jdbc;
    private final OutboxDispatcher dispatcher;
    private final DeadLetterStore deadLetterStore;
    private final FailureClassifier failureClassifier;
    private final RetryBackoff backoff;
    private final Clock clock;
    private final int batchSize;
    private final int maxAttempts;

    public OutboxRelay(JdbcTemplate jdbc, OutboxDispatcher dispatcher,
                       DeadLetterStore deadLetterStore, FailureClassifier failureClassifier,
                       RetryBackoff backoff, Clock clock, int batchSize, int maxAttempts) {
        this.jdbc = jdbc;
        this.dispatcher = dispatcher;
        this.deadLetterStore = deadLetterStore;
        this.failureClassifier = failureClassifier;
        this.backoff = backoff;
        this.clock = clock;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
    }

    @Scheduled(fixedDelayString = "${aipersimmon.ddd.outbox.poll-delay-ms:1000}")
    @SchedulerLock(
            name = "${aipersimmon.ddd.outbox.relay.lock-name:${spring.application.name:aipersimmon}-outbox-relay}",
            lockAtMostFor = "${aipersimmon.ddd.outbox.relay.lock-at-most-for:PT10M}")
    public void relay() {
        Timestamp now = Timestamp.from(clock.instant());
        List<Pending> batch = jdbc.query(SELECT_DUE, this::mapRow, maxAttempts, now, batchSize);
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
                if (handleFailure(pending, e) && subject != null) {
                    blockedSubjects.add(subject);
                }
            }
        }
    }

    /**
     * Handles a failed dispatch. Returns {@code true} if the row remains live (a retry
     * was scheduled), so the caller holds back the rest of its aggregate this round;
     * {@code false} if the row was dead-lettered (given up on) and its aggregate may
     * proceed.
     */
    private boolean handleFailure(Pending pending, RuntimeException error) {
        OutboxMessage message = pending.message();
        int attempts = pending.attempts() + 1;
        if (failureClassifier.classify(error) == FailureClassifier.Failure.PERMANENT) {
            deadLetterStore.store(message, attempts, DeadLetterStore.Reason.PERMANENT, summarize(error));
            log.error("outbox dispatch for eventId={} failed permanently; dead-lettered without retry",
                    message.eventId(), error);
            return false;
        }
        if (attempts >= maxAttempts) {
            deadLetterStore.store(message, attempts, DeadLetterStore.Reason.RETRIES_EXHAUSTED, summarize(error));
            log.error("outbox dispatch for eventId={} failed {} times; dead-lettered",
                    message.eventId(), maxAttempts, error);
            return false;
        }
        Duration delay = backoff.nextDelay(attempts);
        jdbc.update(SCHEDULE_RETRY, Timestamp.from(clock.instant().plus(delay)), message.eventId());
        log.warn("outbox dispatch failed for eventId={}, retrying in {}ms (attempt {}/{})",
                message.eventId(), delay.toMillis(), attempts, maxAttempts, error);
        return true;
    }

    private static String summarize(Throwable error) {
        return error.getClass().getName() + ": " + error.getMessage();
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
