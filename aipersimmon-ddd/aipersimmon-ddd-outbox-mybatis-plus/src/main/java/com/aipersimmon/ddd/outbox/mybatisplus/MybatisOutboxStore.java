package com.aipersimmon.ddd.outbox.mybatisplus;

import com.aipersimmon.ddd.outbox.OutboxMessage;
import com.aipersimmon.ddd.outbox.engine.store.OutboxInsert;
import com.aipersimmon.ddd.outbox.engine.store.OutboxLease;
import com.aipersimmon.ddd.outbox.engine.store.OutboxStore;
import com.aipersimmon.ddd.outbox.engine.store.PendingBacklog;
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
 *
 * <p>Every write goes through an update wrapper rather than {@code updateById}: a wrapper's {@code
 * set} emits the assignment as written, including {@code = null}, whereas MyBatis-Plus's default
 * field strategy drops null fields from an entity update — which is exactly what clearing a lease
 * needs to do.
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
    record.setDestination(row.destination());
    record.setTraceparent(row.traceparent());
    record.setTraceState(row.traceState());
    record.setSent(false);
    record.setAttempts(0);
    record.setCreatedAt(row.createdAt());
    mapper.insert(record);
  }

  @Override
  public List<PendingMessage> claimDue(
      Instant now, int maxAttempts, int batchSize, OutboxLease lease) {
    List<String> candidates = mapper.selectClaimable(maxAttempts, now, batchSize);
    if (candidates.isEmpty()) {
      return List.of();
    }
    // Stamping the lease is the claim, and it re-checks due-and-unleased so that two instances
    // whose candidate lists overlap cannot both win a row: the loser's update matches nothing.
    // It deliberately does not re-check the head-of-aggregate clause — a row that was the head
    // stays the head, because earlier rows only ever leave the live set.
    mapper.update(
        null,
        new LambdaUpdateWrapper<OutboxRecord>()
            .in(OutboxRecord::getEventId, candidates)
            .eq(OutboxRecord::getSent, false)
            .lt(OutboxRecord::getAttempts, maxAttempts)
            .and(
                due ->
                    due.isNull(OutboxRecord::getNextAttemptAt)
                        .or()
                        .le(OutboxRecord::getNextAttemptAt, now))
            .and(
                free ->
                    free.isNull(OutboxRecord::getLeaseUntil)
                        .or()
                        .le(OutboxRecord::getLeaseUntil, now))
            .set(OutboxRecord::getLeaseOwner, lease.owner())
            .set(OutboxRecord::getLeaseToken, lease.token())
            .set(OutboxRecord::getLeaseUntil, lease.until()));
    // Which rows this claim actually won is answered by the database, not by an update count.
    return mapper
        .selectList(
            new LambdaQueryWrapper<OutboxRecord>()
                .eq(OutboxRecord::getLeaseToken, lease.token())
                .orderByAsc(OutboxRecord::getCreatedAt)
                .orderByAsc(OutboxRecord::getId))
        .stream()
        .map(MybatisOutboxStore::toPending)
        .toList();
  }

  @Override
  public void release(List<String> eventIds) {
    if (eventIds.isEmpty()) {
      return;
    }
    mapper.update(
        null,
        clearLease(new LambdaUpdateWrapper<OutboxRecord>()).in(OutboxRecord::getEventId, eventIds));
  }

  @Override
  public void markSent(String eventId, Instant sentAt) {
    mapper.update(
        null,
        clearLease(new LambdaUpdateWrapper<OutboxRecord>())
            .eq(OutboxRecord::getEventId, eventId)
            .set(OutboxRecord::getSent, true)
            .set(OutboxRecord::getSentAt, sentAt));
  }

  @Override
  public void scheduleRetry(String eventId, Instant nextAttemptAt) {
    mapper.update(
        null,
        clearLease(new LambdaUpdateWrapper<OutboxRecord>())
            .eq(OutboxRecord::getEventId, eventId)
            .setSql("attempts = attempts + 1")
            .set(OutboxRecord::getNextAttemptAt, nextAttemptAt));
  }

  @Override
  public void backOffWithoutAttempt(String eventId, Instant nextAttemptAt) {
    mapper.update(
        null,
        clearLease(new LambdaUpdateWrapper<OutboxRecord>())
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

  @Override
  public PendingBacklog pendingBacklog(int maxAttempts) {
    PendingBacklogRow row = mapper.selectPendingBacklog(maxAttempts);
    return row == null
        ? PendingBacklog.EMPTY
        : new PendingBacklog(row.getPending(), row.getOldest());
  }

  /**
   * Drops the lease. Every relay write ends a row's claim — sent, backing off, or handed back — so
   * clearing it is attached to all of them rather than left to be remembered at each call site.
   */
  private static LambdaUpdateWrapper<OutboxRecord> clearLease(
      LambdaUpdateWrapper<OutboxRecord> wrapper) {
    return wrapper
        .set(OutboxRecord::getLeaseOwner, null)
        .set(OutboxRecord::getLeaseToken, null)
        .set(OutboxRecord::getLeaseUntil, null);
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
            record.getCausationId(),
            record.getDestination());
    return new PendingMessage(
        message, record.getAttempts(), record.getTraceparent(), record.getTraceState());
  }
}
