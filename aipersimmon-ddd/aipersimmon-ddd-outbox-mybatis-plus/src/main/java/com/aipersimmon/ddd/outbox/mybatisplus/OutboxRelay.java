package com.aipersimmon.ddd.outbox.mybatisplus;

import com.aipersimmon.ddd.outbox.DeadLetterStore;
import com.aipersimmon.ddd.outbox.FailureClassifier;
import com.aipersimmon.ddd.outbox.OutboxDispatcher;
import com.aipersimmon.ddd.outbox.OutboxMessage;
import com.aipersimmon.ddd.outbox.RetryBackoff;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private final OutboxMapper mapper;
    private final OutboxDispatcher dispatcher;
    private final DeadLetterStore deadLetterStore;
    private final FailureClassifier failureClassifier;
    private final RetryBackoff backoff;
    private final Clock clock;
    private final int batchSize;
    private final int maxAttempts;

    public OutboxRelay(OutboxMapper mapper, OutboxDispatcher dispatcher,
                       DeadLetterStore deadLetterStore, FailureClassifier failureClassifier,
                       RetryBackoff backoff, Clock clock, int batchSize, int maxAttempts) {
        this.mapper = mapper;
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
        java.time.Instant now = clock.instant();
        List<OutboxRecord> batch = mapper.selectDue(maxAttempts, now, batchSize);
        Set<String> blockedSubjects = new HashSet<>();
        for (OutboxRecord record : batch) {
            String subject = orderingKey(record.getSubject());
            if (subject != null && blockedSubjects.contains(subject)) {
                // An earlier event for this aggregate failed this round; hold its
                // later events back so they are not delivered out of order.
                continue;
            }
            try {
                dispatcher.dispatch(toMessage(record));
                mapper.update(null, new LambdaUpdateWrapper<OutboxRecord>()
                        .eq(OutboxRecord::getEventId, record.getEventId())
                        .set(OutboxRecord::getSent, true)
                        .set(OutboxRecord::getSentAt, clock.instant()));
            } catch (RuntimeException e) {
                if (handleFailure(record, e) && subject != null) {
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
    private boolean handleFailure(OutboxRecord record, RuntimeException error) {
        int attempts = record.getAttempts() + 1;
        if (failureClassifier.classify(error) == FailureClassifier.Failure.PERMANENT) {
            deadLetterStore.store(toMessage(record), attempts, DeadLetterStore.Reason.PERMANENT, summarize(error));
            log.error("outbox dispatch for eventId={} failed permanently; dead-lettered without retry",
                    record.getEventId(), error);
            return false;
        }
        if (attempts >= maxAttempts) {
            deadLetterStore.store(
                    toMessage(record), attempts, DeadLetterStore.Reason.RETRIES_EXHAUSTED, summarize(error));
            log.error("outbox dispatch for eventId={} failed {} times; dead-lettered",
                    record.getEventId(), maxAttempts, error);
            return false;
        }
        Duration delay = backoff.nextDelay(attempts);
        mapper.update(null, new LambdaUpdateWrapper<OutboxRecord>()
                .eq(OutboxRecord::getEventId, record.getEventId())
                .setSql("attempts = attempts + 1")
                .set(OutboxRecord::getNextAttemptAt, clock.instant().plus(delay)));
        log.warn("outbox dispatch failed for eventId={}, retrying in {}ms (attempt {}/{})",
                record.getEventId(), delay.toMillis(), attempts, maxAttempts, error);
        return true;
    }

    private static String summarize(Throwable error) {
        return error.getClass().getName() + ": " + error.getMessage();
    }

    /** A null or blank subject carries no per-aggregate ordering key (matching the query
     *  and the Kafka partition key), so it never blocks or is blocked. */
    private static String orderingKey(String subject) {
        return subject == null || subject.isBlank() ? null : subject;
    }

    private static OutboxMessage toMessage(OutboxRecord record) {
        return new OutboxMessage(
                record.getEventId(),
                record.getSource(),
                record.getType(),
                record.getVersion(),
                record.getPayload(),
                record.getOccurredAt(),
                record.getSubject(),
                record.getCorrelationId(),
                record.getCausationId(),
                record.getTraceId());
    }
}
