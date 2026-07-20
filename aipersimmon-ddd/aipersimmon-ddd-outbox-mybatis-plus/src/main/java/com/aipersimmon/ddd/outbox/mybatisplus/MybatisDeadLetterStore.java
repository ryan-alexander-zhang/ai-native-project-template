package com.aipersimmon.ddd.outbox.mybatisplus;

import com.aipersimmon.ddd.outbox.DeadLetterStore;
import com.aipersimmon.ddd.outbox.OutboxMessage;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.time.Clock;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Backs the {@link DeadLetterStore} with the {@code aipersimmon_dead_letter} table via
 * MyBatis-Plus. Every move runs in one {@link TransactionTemplate} transaction — the
 * dead-letter insert and the outbox delete commit together — so a message is never
 * duplicated across the two tables nor lost between them. Mirrors the JDBC starter's
 * store so the two backends behave identically.
 */
public class MybatisDeadLetterStore implements DeadLetterStore {

    private final OutboxMapper outboxMapper;
    private final DeadLetterMapper deadLetterMapper;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public MybatisDeadLetterStore(OutboxMapper outboxMapper, DeadLetterMapper deadLetterMapper,
                                  TransactionTemplate transactionTemplate, Clock clock) {
        this.outboxMapper = outboxMapper;
        this.deadLetterMapper = deadLetterMapper;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    @Override
    public void store(OutboxMessage message, int attempts, Reason reason, String lastError) {
        transactionTemplate.executeWithoutResult(status -> {
            DeadLetterRecord record = new DeadLetterRecord();
            record.setEventId(message.eventId());
            record.setSource(message.source());
            record.setType(message.type());
            record.setVersion(message.version());
            record.setPayload(message.payload());
            record.setOccurredAt(message.occurredAt());
            record.setSubject(message.subject());
            record.setCorrelationId(message.correlationId());
            record.setCausationId(message.causationId());
            record.setAttempts(attempts);
            record.setReason(reason.name());
            record.setLastError(lastError);
            record.setFailedAt(clock.instant());
            deadLetterMapper.insert(record);
            outboxMapper.delete(new LambdaQueryWrapper<OutboxRecord>()
                    .eq(OutboxRecord::getEventId, message.eventId()));
        });
    }

    @Override
    public boolean replay(String eventId) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            DeadLetterRecord dead = deadLetterMapper.selectOne(new LambdaQueryWrapper<DeadLetterRecord>()
                    .eq(DeadLetterRecord::getEventId, eventId));
            if (dead == null) {
                return false;
            }
            OutboxRecord record = new OutboxRecord();
            record.setEventId(dead.getEventId());
            record.setSource(dead.getSource());
            record.setType(dead.getType());
            record.setVersion(dead.getVersion());
            record.setPayload(dead.getPayload());
            record.setOccurredAt(dead.getOccurredAt());
            record.setSubject(dead.getSubject());
            record.setCorrelationId(dead.getCorrelationId());
            record.setCausationId(dead.getCausationId());
            record.setSent(false);
            record.setAttempts(0);
            record.setNextAttemptAt(null);
            record.setCreatedAt(clock.instant());
            outboxMapper.insert(record);
            deadLetterMapper.delete(new LambdaQueryWrapper<DeadLetterRecord>()
                    .eq(DeadLetterRecord::getEventId, eventId));
            return true;
        }));
    }
}
