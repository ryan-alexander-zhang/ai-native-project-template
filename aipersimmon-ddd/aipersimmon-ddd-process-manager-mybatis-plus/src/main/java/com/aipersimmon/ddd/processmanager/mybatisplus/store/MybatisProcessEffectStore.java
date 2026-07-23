package com.aipersimmon.ddd.processmanager.mybatisplus.store;

import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.processmanager.codec.PayloadType;
import com.aipersimmon.ddd.processmanager.effect.ProcessEffectKind;
import com.aipersimmon.ddd.processmanager.engine.store.ClaimedEffect;
import com.aipersimmon.ddd.processmanager.engine.store.EffectStatus;
import com.aipersimmon.ddd.processmanager.engine.store.Payloads;
import com.aipersimmon.ddd.processmanager.engine.store.ProcessEffectInsert;
import com.aipersimmon.ddd.processmanager.engine.store.ProcessEffectStore;
import com.aipersimmon.ddd.processmanager.engine.store.ProcessEffectView;
import com.aipersimmon.ddd.processmanager.model.ProcessInstanceId;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** MyBatis-Plus implementation of {@link ProcessEffectStore}; SQL mirrors the JDBC store. */
public final class MybatisProcessEffectStore implements ProcessEffectStore {

  private final ProcessEffectMapper mapper;

  public MybatisProcessEffectStore(ProcessEffectMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public long nextSeq(ProcessInstanceId instanceId) {
    Long max = mapper.maxSeq(instanceId.value());
    return max == null ? 0L : max + 1L;
  }

  @Override
  public void insert(ProcessEffectInsert e, Instant now) {
    Timestamp ts = Timestamp.from(now);
    Map<String, Object> m = new HashMap<>();
    m.put("effectId", e.effectId());
    m.put("instanceId", e.instanceId().value());
    m.put("transitionId", e.transitionId());
    m.put("effectIndex", e.effectIndex());
    m.put("seq", e.seq());
    m.put("effectKind", e.kind().name());
    m.put("payloadType", e.payloadType());
    m.put("payloadVersion", e.payloadVersion());
    m.put("payload", Payloads.toText(e.payload()));
    m.put("messageId", e.messageId());
    m.put("correlationId", e.correlationId());
    m.put("causationId", e.causationId());
    m.put("traceparent", e.traceparent());
    m.put("traceState", e.traceState());
    m.put("nextAttemptAt", ts);
    m.put("createdAt", ts);
    m.put("updatedAt", ts);
    mapper.insert(m);
  }

  @Override
  public Optional<ClaimedEffect> load(String effectId) {
    EffectLoadRow r = mapper.load(effectId);
    if (r == null) {
      return Optional.empty();
    }
    return Optional.of(
        new ClaimedEffect(
            r.getEffectId(),
            new ProcessInstanceId(r.getInstanceId()),
            ProcessEffectKind.valueOf(r.getEffectKind()),
            new PayloadType(r.getPayloadType(), r.getPayloadVersion()),
            Payloads.fromText(r.getPayload()),
            new CommandContext(r.getMessageId(), r.getCorrelationId(), r.getCausationId()),
            r.getAttempts(),
            r.getTraceparent(),
            r.getTraceState()));
  }

  @Override
  public int markDelivered(String effectId, String leaseToken, Instant now) {
    return mapper.markDelivered(effectId, leaseToken, Timestamp.from(now));
  }

  @Override
  public int scheduleRetry(
      String effectId, String leaseToken, Instant nextAttemptAt, String error, Instant now) {
    return mapper.scheduleRetry(
        effectId, leaseToken, Timestamp.from(nextAttemptAt), error, Timestamp.from(now));
  }

  @Override
  public int markDead(String effectId, String leaseToken, String error, Instant now) {
    return mapper.markDead(effectId, leaseToken, error, Timestamp.from(now));
  }

  @Override
  public int markCancelled(String effectId, String leaseToken, Instant now) {
    return mapper.markCancelled(effectId, leaseToken, Timestamp.from(now));
  }

  @Override
  public int redrive(String effectId, Instant now) {
    return mapper.redrive(effectId, Timestamp.from(now));
  }

  @Override
  public int cancelPending(ProcessInstanceId instanceId, Instant now) {
    return mapper.cancelPending(instanceId.value(), Timestamp.from(now));
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
  public List<ProcessEffectView> byStatus(EffectStatus status, int limit) {
    return mapper.byStatus(status.name(), limit).stream()
        .map(
            r ->
                new ProcessEffectView(
                    r.getEffectId(),
                    r.getInstanceId(),
                    r.getEffectKind(),
                    r.getStatus(),
                    r.getAttempts(),
                    r.getMessageId(),
                    Optional.ofNullable(r.getNextAttemptAt()).map(Timestamp::toInstant),
                    Optional.ofNullable(r.getLastError()),
                    r.getCreatedAt().toInstant()))
        .toList();
  }
}
