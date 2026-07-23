package com.aipersimmon.ddd.processmanager.mybatisplus.store;

import com.aipersimmon.ddd.processmanager.codec.PayloadType;
import com.aipersimmon.ddd.processmanager.engine.store.ConcurrentTransitionException;
import com.aipersimmon.ddd.processmanager.engine.store.ParkedInput;
import com.aipersimmon.ddd.processmanager.engine.store.Payloads;
import com.aipersimmon.ddd.processmanager.engine.store.ProcessTransitionInsert;
import com.aipersimmon.ddd.processmanager.engine.store.ProcessTransitionStore;
import com.aipersimmon.ddd.processmanager.engine.store.ProcessTransitionView;
import com.aipersimmon.ddd.processmanager.model.ProcessInstanceId;
import com.aipersimmon.ddd.processmanager.model.ProcessLifecycle;
import com.aipersimmon.ddd.processmanager.model.ProcessStep;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;

/**
 * MyBatis-Plus implementation of {@link ProcessTransitionStore}. SQL mirrors {@code
 * JdbcProcessTransitionStore}; the {@code UNIQUE(instance_id, input_message_id)} violation on
 * {@link #append} is surfaced as {@link ConcurrentTransitionException} so the runtime retries it
 * store-neutrally.
 */
public final class MybatisProcessTransitionStore implements ProcessTransitionStore {

  private final ProcessTransitionMapper mapper;

  public MybatisProcessTransitionStore(ProcessTransitionMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public Optional<String> findTransitionIdByInput(
      ProcessInstanceId instanceId, String inputMessageId) {
    return Optional.ofNullable(mapper.findTransitionIdByInput(instanceId.value(), inputMessageId));
  }

  @Override
  public Optional<String> findLatestTransitionId(ProcessInstanceId instanceId) {
    return Optional.ofNullable(mapper.findLatestTransitionId(instanceId.value()));
  }

  private long nextTransitionSeq(ProcessInstanceId instanceId) {
    Long max = mapper.maxTransitionSeq(instanceId.value());
    return max == null ? 0L : max + 1L;
  }

  @Override
  public void append(ProcessTransitionInsert t, Instant now) {
    Map<String, Object> m = new HashMap<>();
    m.put("transitionId", t.transitionId());
    m.put("instanceId", t.instanceId().value());
    m.put("transitionSeq", nextTransitionSeq(t.instanceId()));
    m.put("inputMessageId", t.inputMessageId());
    m.put("inputType", t.inputType());
    m.put("inputVersion", t.inputVersion());
    m.put("inputPayload", Payloads.toText(t.inputPayload()));
    m.put("fromLifecycle", t.fromLifecycle().map(ProcessLifecycle::name).orElse(null));
    m.put("toLifecycle", t.toLifecycle().name());
    m.put("fromStep", t.fromStep().map(ProcessStep::value).orElse(null));
    m.put("toStep", t.toStep().value());
    m.put("decisionCode", t.decisionCode().value());
    m.put("transitionKind", t.transitionKind());
    m.put("correlationId", t.correlationId());
    m.put("createdAt", Timestamp.from(now));
    try {
      mapper.append(m);
    } catch (DuplicateKeyException alreadyRecorded) {
      throw new ConcurrentTransitionException(
          "transition for input "
              + t.inputMessageId()
              + " on instance "
              + t.instanceId().value()
              + " already recorded",
          alreadyRecorded);
    }
  }

  @Override
  public void appendOperator(
      String transitionId,
      ProcessInstanceId instanceId,
      ProcessLifecycle fromLifecycle,
      ProcessLifecycle toLifecycle,
      ProcessStep fromStep,
      ProcessStep toStep,
      String kind,
      String operator,
      String reason,
      Instant now) {
    Map<String, Object> m = new HashMap<>();
    m.put("transitionId", transitionId);
    m.put("instanceId", instanceId.value());
    m.put("transitionSeq", nextTransitionSeq(instanceId));
    m.put("inputMessageId", transitionId);
    m.put("inputType", "aipersimmon.operator");
    m.put("inputVersion", 1);
    m.put(
        "inputPayload",
        Payloads.toText((reason == null ? "" : reason).getBytes(StandardCharsets.UTF_8)));
    m.put("fromLifecycle", fromLifecycle.name());
    m.put("toLifecycle", toLifecycle.name());
    m.put("fromStep", fromStep.value());
    m.put("toStep", toStep.value());
    m.put("decisionCode", kind.toLowerCase(Locale.ROOT));
    m.put("transitionKind", kind);
    m.put("correlationId", null);
    m.put("operatorId", operator);
    m.put("operationReason", reason);
    m.put("createdAt", Timestamp.from(now));
    mapper.appendOperator(m);
  }

  @Override
  public List<ProcessTransitionView> timeline(ProcessInstanceId instanceId) {
    return mapper.timeline(instanceId.value()).stream()
        .map(
            r ->
                new ProcessTransitionView(
                    r.getTransitionId(),
                    r.getInputMessageId(),
                    Optional.ofNullable(r.getFromLifecycle()),
                    r.getToLifecycle(),
                    Optional.ofNullable(r.getFromStep()),
                    r.getToStep(),
                    r.getDecisionCode(),
                    r.getTransitionKind(),
                    Optional.ofNullable(r.getOperatorId()),
                    Optional.ofNullable(r.getOperationReason()),
                    r.getCreatedAt().toInstant()))
        .toList();
  }

  @Override
  public List<ParkedInput> findParkedInputs(ProcessInstanceId instanceId) {
    return mapper.findParkedInputs(instanceId.value()).stream()
        .map(
            r ->
                new ParkedInput(
                    r.getInputMessageId(),
                    new PayloadType(r.getInputType(), r.getInputVersion()),
                    Payloads.fromText(r.getInputPayload()),
                    r.getCorrelationId()))
        .toList();
  }
}
