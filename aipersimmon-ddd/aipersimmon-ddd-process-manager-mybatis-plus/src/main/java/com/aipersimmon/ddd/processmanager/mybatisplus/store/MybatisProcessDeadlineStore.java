package com.aipersimmon.ddd.processmanager.mybatisplus.store;

import com.aipersimmon.ddd.processmanager.codec.PayloadType;
import com.aipersimmon.ddd.processmanager.engine.store.DeadlineRow;
import com.aipersimmon.ddd.processmanager.engine.store.DeadlineStatus;
import com.aipersimmon.ddd.processmanager.engine.store.Payloads;
import com.aipersimmon.ddd.processmanager.engine.store.ProcessDeadlineInsert;
import com.aipersimmon.ddd.processmanager.engine.store.ProcessDeadlineStore;
import com.aipersimmon.ddd.processmanager.engine.store.ProcessDeadlineView;
import com.aipersimmon.ddd.processmanager.model.DeadlineName;
import com.aipersimmon.ddd.processmanager.model.ProcessInstanceId;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** MyBatis-Plus implementation of {@link ProcessDeadlineStore}; SQL mirrors the JDBC store. */
public final class MybatisProcessDeadlineStore implements ProcessDeadlineStore {

  private final ProcessDeadlineMapper mapper;

  public MybatisProcessDeadlineStore(ProcessDeadlineMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public long nextGeneration(ProcessInstanceId instanceId, DeadlineName name) {
    Long max = mapper.maxGeneration(instanceId.value(), name.value());
    return max == null ? 1L : max + 1L;
  }

  @Override
  public long currentGeneration(ProcessInstanceId instanceId, DeadlineName name) {
    Long max = mapper.maxGeneration(instanceId.value(), name.value());
    return max == null ? 0L : max;
  }

  @Override
  public void schedule(ProcessDeadlineInsert d, Instant now) {
    Timestamp ts = Timestamp.from(now);
    Timestamp due = Timestamp.from(d.dueAt());
    Map<String, Object> m = new HashMap<>();
    m.put("deadlineId", d.deadlineId());
    m.put("instanceId", d.instanceId().value());
    m.put("name", d.name().value());
    m.put("generation", d.generation());
    m.put("dueAt", due);
    m.put("inputType", d.inputType());
    m.put("inputVersion", d.inputVersion());
    m.put("inputPayload", Payloads.toText(d.inputPayload()));
    m.put("correlationId", d.correlationId());
    m.put("causationId", d.causationId());
    m.put("traceparent", d.traceparent());
    m.put("traceState", d.traceState());
    m.put("nextAttemptAt", due);
    m.put("createdAt", ts);
    m.put("updatedAt", ts);
    mapper.schedule(m);
  }

  @Override
  public void cancelCurrent(ProcessInstanceId instanceId, DeadlineName name, Instant now) {
    long current = currentGeneration(instanceId, name);
    if (current == 0L) {
      return;
    }
    mapper.cancelCurrent(instanceId.value(), name.value(), current, Timestamp.from(now));
  }

  @Override
  public int cancelPending(ProcessInstanceId instanceId, Instant now) {
    return mapper.cancelPending(instanceId.value(), Timestamp.from(now));
  }

  @Override
  public int cancelClaimed(String deadlineId, String leaseToken, Instant now) {
    return mapper.cancelClaimed(deadlineId, leaseToken, Timestamp.from(now));
  }

  @Override
  public Optional<DeadlineStatus> statusForUpdate(String deadlineId) {
    return Optional.ofNullable(mapper.statusForUpdate(deadlineId)).map(DeadlineStatus::valueOf);
  }

  @Override
  public Optional<DeadlineRow> load(String deadlineId) {
    DeadlineLoadRow r = mapper.load(deadlineId);
    if (r == null) {
      return Optional.empty();
    }
    return Optional.of(
        new DeadlineRow(
            r.getDeadlineId(),
            new ProcessInstanceId(r.getInstanceId()),
            new DeadlineName(r.getName()),
            r.getGeneration(),
            new PayloadType(r.getInputType(), r.getInputVersion()),
            Payloads.fromText(r.getInputPayload()),
            r.getCorrelationId(),
            r.getCausationId(),
            r.getAttempts(),
            r.getTraceparent(),
            r.getTraceState()));
  }

  @Override
  public int markFired(String deadlineId, String leaseToken, Instant now) {
    return mapper.markFired(deadlineId, leaseToken, Timestamp.from(now));
  }

  @Override
  public int scheduleRetry(
      String deadlineId, String leaseToken, Instant nextAttemptAt, String error, Instant now) {
    return mapper.scheduleRetry(
        deadlineId, leaseToken, Timestamp.from(nextAttemptAt), error, Timestamp.from(now));
  }

  @Override
  public int markDead(String deadlineId, String leaseToken, String error, Instant now) {
    return mapper.markDead(deadlineId, leaseToken, error, Timestamp.from(now));
  }

  @Override
  public int redrive(String deadlineId, Instant now) {
    return mapper.redrive(deadlineId, Timestamp.from(now));
  }

  @Override
  public long countDead() {
    return mapper.countDeadAll();
  }

  @Override
  public long countDead(ProcessInstanceId instanceId) {
    return mapper.countDeadForInstance(instanceId.value());
  }

  @Override
  public Optional<Instant> oldestDuePending(Instant now) {
    return Optional.ofNullable(mapper.oldestDuePending(Timestamp.from(now)))
        .map(Timestamp::toInstant);
  }

  @Override
  public List<ProcessDeadlineView> byStatus(DeadlineStatus status, int limit) {
    return mapper.byStatus(status.name(), limit).stream()
        .map(
            r ->
                new ProcessDeadlineView(
                    r.getDeadlineId(),
                    r.getInstanceId(),
                    r.getName(),
                    r.getGeneration(),
                    r.getStatus(),
                    r.getDueAt().toInstant(),
                    r.getAttempts(),
                    Optional.ofNullable(r.getNextAttemptAt()).map(Timestamp::toInstant),
                    Optional.ofNullable(r.getLastError())))
        .toList();
  }
}
