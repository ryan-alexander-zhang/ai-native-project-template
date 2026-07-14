package com.aipersimmon.ddd.outbox.mybatisplus;

import com.aipersimmon.ddd.outbox.OutboxDispatcher;
import com.aipersimmon.ddd.outbox.OutboxMessage;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private final OutboxMapper mapper;
    private final OutboxDispatcher dispatcher;
    private final Clock clock;
    private final int batchSize;
    private final int maxAttempts;

    public OutboxRelay(OutboxMapper mapper, OutboxDispatcher dispatcher, Clock clock,
                       int batchSize, int maxAttempts) {
        this.mapper = mapper;
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
        LambdaQueryWrapper<OutboxRecord> unsent = new LambdaQueryWrapper<OutboxRecord>()
                .eq(OutboxRecord::getSent, false)
                .lt(OutboxRecord::getAttempts, maxAttempts)
                .orderByAsc(OutboxRecord::getCreatedAt)
                .orderByAsc(OutboxRecord::getId)
                .last("LIMIT " + batchSize);
        List<OutboxRecord> batch = mapper.selectList(unsent);
        Set<String> blockedSubjects = new HashSet<>();
        for (OutboxRecord record : batch) {
            String subject = record.getSubject();
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
                mapper.update(null, new LambdaUpdateWrapper<OutboxRecord>()
                        .eq(OutboxRecord::getEventId, record.getEventId())
                        .setSql("attempts = attempts + 1"));
                if (subject != null) {
                    blockedSubjects.add(subject);
                }
                if (record.getAttempts() + 1 >= maxAttempts) {
                    log.error("outbox dispatch for eventId={} failed {} times; giving up. The row "
                            + "stays unsent as a dead letter and the relay will no longer select it.",
                            record.getEventId(), maxAttempts, e);
                } else {
                    log.warn("outbox dispatch failed for eventId={}, will retry", record.getEventId(), e);
                }
            }
        }
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
