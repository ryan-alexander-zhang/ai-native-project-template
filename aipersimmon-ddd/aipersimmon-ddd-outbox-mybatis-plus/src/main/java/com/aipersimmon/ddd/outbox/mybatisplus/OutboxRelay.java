package com.aipersimmon.ddd.outbox.mybatisplus;

import com.aipersimmon.ddd.outbox.OutboxDispatcher;
import com.aipersimmon.ddd.outbox.OutboxMessage;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Polls the outbox for unsent rows and dispatches them, marking each sent on
 * success. A failed dispatch leaves the row unsent (its attempt count is bumped)
 * so it is retried on the next poll — at-least-once delivery. Each row is marked
 * on its own, so one failure does not undo already-dispatched rows.
 */
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxMapper mapper;
    private final OutboxDispatcher dispatcher;
    private final Clock clock;
    private final int batchSize;

    public OutboxRelay(OutboxMapper mapper, OutboxDispatcher dispatcher, Clock clock, int batchSize) {
        this.mapper = mapper;
        this.dispatcher = dispatcher;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${aipersimmon.ddd.outbox.poll-delay-ms:1000}")
    public void relay() {
        LambdaQueryWrapper<OutboxRecord> unsent = new LambdaQueryWrapper<OutboxRecord>()
                .eq(OutboxRecord::getSent, false)
                .orderByAsc(OutboxRecord::getCreatedAt)
                .last("LIMIT " + batchSize);
        List<OutboxRecord> batch = mapper.selectList(unsent);
        for (OutboxRecord record : batch) {
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
                log.warn("outbox dispatch failed for eventId={}, will retry", record.getEventId(), e);
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
