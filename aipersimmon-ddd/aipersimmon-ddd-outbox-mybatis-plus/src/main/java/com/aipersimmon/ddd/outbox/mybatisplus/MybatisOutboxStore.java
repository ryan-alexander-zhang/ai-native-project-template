package com.aipersimmon.ddd.outbox.mybatisplus;

import com.aipersimmon.ddd.outbox.OutboxMessage;
import com.aipersimmon.ddd.outbox.engine.store.OutboxInsert;
import com.aipersimmon.ddd.outbox.engine.store.OutboxStore;
import com.aipersimmon.ddd.outbox.engine.store.PendingMessage;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import java.time.Instant;
import java.util.List;

/**
 * {@link OutboxStore} over {@code aipersimmon_outbox} through the MyBatis-Plus {@link
 * OutboxMapper}. Mapper calls only — every decision about ordering, retries and giving up lives in
 * the engine, so this and {@code JdbcOutboxStore} are two spellings of the same table access rather
 * than two implementations of the same behaviour.
 */
public class MybatisOutboxStore implements OutboxStore {

  private final OutboxMapper mapper;

  public MybatisOutboxStore(OutboxMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public void insert(OutboxInsert row) {
    OutboxRecord record = new OutboxRecord();
    record.setEventId(row.eventId());
    record.setSource(row.source());
    record.setType(row.type());
    record.setVersion(row.version());
    record.setPayload(row.payload());
    record.setOccurredAt(row.occurredAt());
    record.setSubject(row.subject());
    record.setTenantId(row.tenantId());
    record.setCorrelationId(row.correlationId());
    record.setCausationId(row.causationId());
    record.setTraceparent(row.traceparent());
    record.setTraceState(row.traceState());
    record.setSent(false);
    record.setAttempts(0);
    record.setCreatedAt(row.createdAt());
    mapper.insert(record);
  }

  @Override
  public List<PendingMessage> findDue(Instant now, int maxAttempts, int batchSize) {
    return mapper.selectDue(maxAttempts, now, batchSize).stream()
        .map(MybatisOutboxStore::toPending)
        .toList();
  }

  @Override
  public void markSent(String eventId, Instant sentAt) {
    mapper.update(
        null,
        new LambdaUpdateWrapper<OutboxRecord>()
            .eq(OutboxRecord::getEventId, eventId)
            .set(OutboxRecord::getSent, true)
            .set(OutboxRecord::getSentAt, sentAt));
  }

  @Override
  public void scheduleRetry(String eventId, Instant nextAttemptAt) {
    mapper.update(
        null,
        new LambdaUpdateWrapper<OutboxRecord>()
            .eq(OutboxRecord::getEventId, eventId)
            .setSql("attempts = attempts + 1")
            .set(OutboxRecord::getNextAttemptAt, nextAttemptAt));
  }

  @Override
  public void backOffWithoutAttempt(String eventId, Instant nextAttemptAt) {
    mapper.update(
        null,
        new LambdaUpdateWrapper<OutboxRecord>()
            .eq(OutboxRecord::getEventId, eventId)
            .set(OutboxRecord::getNextAttemptAt, nextAttemptAt));
  }

  @Override
  public int deleteSentBefore(Instant sentBefore) {
    return mapper.delete(
        new LambdaQueryWrapper<OutboxRecord>()
            .eq(OutboxRecord::getSent, true)
            .lt(OutboxRecord::getSentAt, sentBefore));
  }

  private static PendingMessage toPending(OutboxRecord record) {
    OutboxMessage message =
        new OutboxMessage(
            record.getEventId(),
            record.getSource(),
            record.getType(),
            record.getVersion(),
            record.getPayload(),
            record.getOccurredAt(),
            record.getSubject(),
            record.getTenantId(),
            record.getCorrelationId(),
            record.getCausationId());
    return new PendingMessage(
        message, record.getAttempts(), record.getTraceparent(), record.getTraceState());
  }
}
