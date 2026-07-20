package com.aipersimmon.ddd.outbox.jdbc;

import com.aipersimmon.ddd.outbox.DeadLetterStore;
import com.aipersimmon.ddd.outbox.FailureClassifier;
import com.aipersimmon.ddd.outbox.OutboxDispatcher;
import com.aipersimmon.ddd.outbox.OutboxMessage;
import com.aipersimmon.ddd.outbox.RetryBackoff;
import com.aipersimmon.ddd.observability.NoOpStoreAndForwardTracer;
import com.aipersimmon.ddd.observability.StoreAndForwardTracer;
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
            "SELECT o.event_id, o.source, o.type, o.version, o.payload, o.occurred_at, o.subject, "
            + "o.correlation_id, o.causation_id, o.traceparent, o.trace_state, o.attempts "
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
    // Backoff without counting an attempt: used when a give-up row cannot be dead-lettered
    // (the dead-letter store is down). Pushing next_attempt_at spaces the retries; leaving
    // attempts untouched keeps the row selectable so it keeps trying the move until the store
    // recovers, rather than crossing max-attempts and being silently stranded.
    private static final String SCHEDULE_BACKOFF =
            "UPDATE aipersimmon_outbox SET next_attempt_at = ? WHERE event_id = ?";

    private final JdbcTemplate jdbc;
    private final OutboxDispatcher dispatcher;
    private final DeadLetterStore deadLetterStore;
    private final FailureClassifier failureClassifier;
    private final RetryBackoff backoff;
    private final Clock clock;
    private final int batchSize;
    private final int maxAttempts;
    private final StoreAndForwardTracer tracer;

    public OutboxRelay(JdbcTemplate jdbc, OutboxDispatcher dispatcher,
                       DeadLetterStore deadLetterStore, FailureClassifier failureClassifier,
                       RetryBackoff backoff, Clock clock, int batchSize, int maxAttempts) {
        this(jdbc, dispatcher, deadLetterStore, failureClassifier, backoff, clock, batchSize, maxAttempts,
                NoOpStoreAndForwardTracer.INSTANCE);
    }

    public OutboxRelay(JdbcTemplate jdbc, OutboxDispatcher dispatcher,
                       DeadLetterStore deadLetterStore, FailureClassifier failureClassifier,
                       RetryBackoff backoff, Clock clock, int batchSize, int maxAttempts,
                       StoreAndForwardTracer tracer) {
        this.jdbc = jdbc;
        this.dispatcher = dispatcher;
        this.deadLetterStore = deadLetterStore;
        this.failureClassifier = failureClassifier;
        this.backoff = backoff;
        this.clock = clock;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.tracer = tracer;
    }

    @Scheduled(fixedDelayString = "${aipersimmon.ddd.outbox.poll-delay-ms:1000}")
    @SchedulerLock(
            name = "${aipersimmon.ddd.outbox.relay.lock-name:${spring.application.name:aipersimmon}-outbox-relay}",
            lockAtMostFor = "${aipersimmon.ddd.outbox.relay.lock-at-most-for:PT10M}")
    public void relay() {
        Timestamp now = Timestamp.from(clock.instant());
        List<Pending> batch = jdbc.query(SELECT_DUE, this::mapRow, maxAttempts, now, maxAttempts, now, batchSize);
        Set<String> blockedSubjects = new HashSet<>();
        for (Pending pending : batch) {
            OutboxMessage message = pending.message();
            String subject = orderingKey(message.subject());
            if (subject != null && blockedSubjects.contains(subject)) {
                // An earlier event for this aggregate failed this round; hold its
                // later events back so they are not delivered out of order.
                continue;
            }
            try (StoreAndForwardTracer.Scope span =
                    tracer.restore(pending.traceparent(), pending.traceState(),
                            "outbox.publish " + message.eventId())) {
                // The restored span is current here, so the Kafka producer instrumentation stamps
                // the message headers with this dispatch span — which links back to the span that
                // wrote the row — rather than with the (unrelated) scheduler thread's context.
                try {
                    dispatcher.dispatch(message);
                } catch (RuntimeException e) {
                    span.recordFailure(e);
                    throw e;
                }
            } catch (RuntimeException e) {
                if (handleFailure(pending, e) && subject != null) {
                    blockedSubjects.add(subject);
                }
                continue;
            }
            // The message is delivered (at-least-once satisfied). A failure to record that is
            // NOT a dispatch failure: never dead-letter or count it against the retry budget —
            // that would discard or misreport a message the broker already has. Leave the row
            // unsent so the next poll re-dispatches it (an accepted at-least-once duplicate,
            // which the consumer's inbox dedups).
            try {
                jdbc.update(MARK_SENT, Timestamp.from(clock.instant()), message.eventId());
            } catch (RuntimeException e) {
                log.warn("outbox dispatch for eventId={} succeeded but marking it sent failed; "
                        + "it will be re-dispatched (a duplicate) on the next poll", message.eventId(), e);
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
            return !deadLetter(message, attempts, DeadLetterStore.Reason.PERMANENT, error,
                    "failed permanently; dead-lettered without retry");
        }
        if (attempts >= maxAttempts) {
            return !deadLetter(message, attempts, DeadLetterStore.Reason.RETRIES_EXHAUSTED, error,
                    "failed " + maxAttempts + " times; dead-lettered");
        }
        Duration delay = backoff.nextDelay(attempts);
        jdbc.update(SCHEDULE_RETRY, Timestamp.from(clock.instant().plus(delay)), message.eventId());
        log.warn("outbox dispatch failed for eventId={}, retrying in {}ms (attempt {}/{})",
                message.eventId(), delay.toMillis(), attempts, maxAttempts, error);
        return true;
    }

    /**
     * Moves a given-up row to the {@link DeadLetterStore} (out of the outbox). If that move
     * fails — the store is unavailable — it does not let the failure propagate and abort the
     * poll, nor leave the row with no {@code next_attempt_at} (which would have the poll
     * re-select and re-dispatch it every second). Instead it backs the row off, so the move is
     * retried at the backoff cadence and self-heals once the store recovers.
     *
     * @return {@code true} if the row was moved out (its aggregate may proceed); {@code false}
     *         if the move failed and a backoff was scheduled instead (the row stays live)
     */
    private boolean deadLetter(OutboxMessage message, int attempts, DeadLetterStore.Reason reason,
                               RuntimeException error, String givingUp) {
        try {
            deadLetterStore.store(message, attempts, reason, summarize(error));
            log.error("outbox dispatch for eventId={} {}", message.eventId(), givingUp, error);
            return true;
        } catch (RuntimeException storeError) {
            storeError.addSuppressed(error);
            Duration delay = backoff.nextDelay(attempts);
            jdbc.update(SCHEDULE_BACKOFF, Timestamp.from(clock.instant().plus(delay)), message.eventId());
            log.error("outbox dead-letter move for eventId={} failed; backing off {}ms so it is not "
                    + "re-dispatched every poll (retried until the dead-letter store recovers)",
                    message.eventId(), delay.toMillis(), storeError);
            return false;
        }
    }

    private static String summarize(Throwable error) {
        return error.getClass().getName() + ": " + error.getMessage();
    }

    /** A null or blank subject carries no per-aggregate ordering key (matching the SELECT
     *  and the Kafka partition key), so it never blocks or is blocked. */
    private static String orderingKey(String subject) {
        return subject == null || subject.isBlank() ? null : subject;
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
                rs.getString("causation_id"));
        return new Pending(message, rs.getInt("attempts"),
                rs.getString("traceparent"), rs.getString("trace_state"));
    }

    /**
     * A selected outbox row: the dispatcher-facing message, its current attempt count, and the
     * captured trace context ({@code traceparent}/{@code trace_state}) to restore on dispatch.
     */
    private record Pending(OutboxMessage message, int attempts, String traceparent, String traceState) {
    }
}
