package com.aipersimmon.ddd.processmanager.mybatisplus.store;

import com.aipersimmon.ddd.processmanager.engine.store.Payloads;
import com.aipersimmon.ddd.processmanager.engine.store.ProcessInstanceCriteria;
import com.aipersimmon.ddd.processmanager.engine.store.ProcessInstanceRow;
import com.aipersimmon.ddd.processmanager.engine.store.ProcessInstanceStore;
import com.aipersimmon.ddd.processmanager.engine.store.VersionRef;
import com.aipersimmon.ddd.processmanager.model.DefinitionVersion;
import com.aipersimmon.ddd.processmanager.model.ProcessBusinessKey;
import com.aipersimmon.ddd.processmanager.model.ProcessInstanceId;
import com.aipersimmon.ddd.processmanager.model.ProcessLifecycle;
import com.aipersimmon.ddd.processmanager.model.ProcessOutcome;
import com.aipersimmon.ddd.processmanager.model.ProcessRef;
import com.aipersimmon.ddd.processmanager.model.ProcessRevision;
import com.aipersimmon.ddd.processmanager.model.ProcessStep;
import com.aipersimmon.ddd.processmanager.model.ProcessType;
import com.aipersimmon.ddd.processmanager.model.StateSchemaVersion;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * MyBatis-Plus implementation of {@link ProcessInstanceStore}. It runs the same SQL as {@code
 * JdbcProcessInstanceStore} through {@link ProcessInstanceMapper} and applies the same value-object
 * (un)wrapping and optimistic-revision guard.
 */
public final class MybatisProcessInstanceStore implements ProcessInstanceStore {

  private final ProcessInstanceMapper mapper;

  public MybatisProcessInstanceStore(ProcessInstanceMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public Optional<ProcessInstanceRow> find(ProcessInstanceId instanceId) {
    return Optional.ofNullable(mapper.find(instanceId.value()))
        .map(MybatisProcessInstanceStore::toRow);
  }

  @Override
  public Optional<ProcessInstanceRow> findForUpdate(ProcessInstanceId instanceId) {
    return Optional.ofNullable(mapper.findForUpdate(instanceId.value()))
        .map(MybatisProcessInstanceStore::toRow);
  }

  @Override
  public Optional<ProcessInstanceRow> findByBusinessKey(
      ProcessType processType, ProcessBusinessKey businessKey) {
    return Optional.ofNullable(mapper.findByBusinessKey(processType.value(), businessKey.value()))
        .map(MybatisProcessInstanceStore::toRow);
  }

  @Override
  public Optional<ProcessInstanceRow> readByBusinessKey(
      ProcessType processType, ProcessBusinessKey businessKey) {
    return Optional.ofNullable(mapper.readByBusinessKey(processType.value(), businessKey.value()))
        .map(MybatisProcessInstanceStore::toRow);
  }

  @Override
  public void insert(ProcessInstanceRow row, Instant now) {
    Timestamp ts = Timestamp.from(now);
    Map<String, Object> m = new HashMap<>();
    m.put("instanceId", row.ref().instanceId().value());
    m.put("processType", row.ref().processType().value());
    m.put("businessKey", row.ref().businessKey().value());
    m.put("definitionVersion", row.definitionVersion().value());
    m.put("stateSchemaVersion", row.stateSchemaVersion().value());
    m.put("lifecycle", row.lifecycle().name());
    m.put("resumeLifecycle", row.resumeLifecycle().map(ProcessLifecycle::name).orElse(null));
    m.put("suspensionReason", row.suspensionReason().orElse(null));
    m.put("businessStep", row.step().value());
    m.put("outcome", row.outcome().map(ProcessOutcome::value).orElse(null));
    m.put("revision", row.revision().value());
    m.put("statePayloadType", row.statePayloadType());
    m.put("statePayload", Payloads.toText(row.statePayload()));
    m.put("createdAt", ts);
    m.put("updatedAt", ts);
    m.put("endedAt", row.lifecycle().isTerminal() ? ts : null);
    mapper.insert(m);
  }

  @Override
  public int updateSnapshot(ProcessInstanceRow row, ProcessRevision expectedRevision, Instant now) {
    Timestamp ts = Timestamp.from(now);
    Map<String, Object> m = new HashMap<>();
    m.put("lifecycle", row.lifecycle().name());
    m.put("resumeLifecycle", row.resumeLifecycle().map(ProcessLifecycle::name).orElse(null));
    m.put("suspensionReason", row.suspensionReason().orElse(null));
    m.put("businessStep", row.step().value());
    m.put("outcome", row.outcome().map(ProcessOutcome::value).orElse(null));
    m.put("revision", row.revision().value());
    m.put("statePayloadType", row.statePayloadType());
    m.put("statePayload", Payloads.toText(row.statePayload()));
    m.put("updatedAt", ts);
    m.put("endedAt", row.lifecycle().isTerminal() ? ts : null);
    m.put("instanceId", row.ref().instanceId().value());
    m.put("expectedRevision", expectedRevision.value());
    return mapper.updateSnapshot(m);
  }

  @Override
  public void suspend(
      ProcessInstanceId instanceId,
      ProcessLifecycle resumeLifecycle,
      String reason,
      String source,
      String workId,
      Instant now) {
    mapper.suspend(
        instanceId.value(), resumeLifecycle.name(), reason, source, workId, Timestamp.from(now));
  }

  @Override
  public void resume(ProcessInstanceId instanceId, ProcessLifecycle toLifecycle, Instant now) {
    mapper.resume(instanceId.value(), toLifecycle.name(), Timestamp.from(now));
  }

  @Override
  public Map<String, Long> countSuspendedBySource() {
    Map<String, Long> bySource = new LinkedHashMap<>();
    for (CountBySource c : mapper.countSuspendedBySource()) {
      bySource.put(c.getSrc(), c.getCnt());
    }
    return bySource;
  }

  @Override
  public long countStuck(Instant updatedBefore) {
    return mapper.countStuck(Timestamp.from(updatedBefore));
  }

  @Override
  public List<ProcessInstanceRow> search(ProcessInstanceCriteria criteria, int limit, int offset) {
    List<InstanceRow> rows =
        mapper.search(
            criteria.processType().map(ProcessType::value).orElse(null),
            criteria.businessKey().map(ProcessBusinessKey::value).orElse(null),
            criteria.lifecycle().map(ProcessLifecycle::name).orElse(null),
            criteria.step().map(ProcessStep::value).orElse(null),
            criteria.definitionVersion().map(DefinitionVersion::value).orElse(null),
            limit,
            offset);
    return rows.stream().map(MybatisProcessInstanceStore::toRow).toList();
  }

  @Override
  public List<ProcessInstanceRow> findStuck(Instant updatedBefore, int limit) {
    return mapper.findStuck(Timestamp.from(updatedBefore), limit).stream()
        .map(MybatisProcessInstanceStore::toRow)
        .toList();
  }

  @Override
  public List<VersionRef> distinctVersionsInUse() {
    return mapper.distinctVersionsInUse().stream()
        .map(
            r ->
                new VersionRef(
                    new ProcessType(r.getProcessType()),
                    new DefinitionVersion(r.getDefinitionVersion()),
                    new StateSchemaVersion(r.getStateSchemaVersion())))
        .toList();
  }

  private static ProcessInstanceRow toRow(InstanceRow r) {
    ProcessRef ref =
        new ProcessRef(
            new ProcessInstanceId(r.getInstanceId()),
            new ProcessType(r.getProcessType()),
            new ProcessBusinessKey(r.getBusinessKey()));
    return new ProcessInstanceRow(
        ref,
        new DefinitionVersion(r.getDefinitionVersion()),
        new StateSchemaVersion(r.getStateSchemaVersion()),
        ProcessLifecycle.valueOf(r.getLifecycle()),
        new ProcessStep(r.getBusinessStep()),
        Optional.ofNullable(r.getOutcome()).map(ProcessOutcome::new),
        new ProcessRevision(r.getRevision()),
        r.getStatePayloadType(),
        Payloads.fromText(r.getStatePayload()),
        Optional.ofNullable(r.getResumeLifecycle()).map(ProcessLifecycle::valueOf),
        Optional.ofNullable(r.getSuspensionReason()));
  }
}
