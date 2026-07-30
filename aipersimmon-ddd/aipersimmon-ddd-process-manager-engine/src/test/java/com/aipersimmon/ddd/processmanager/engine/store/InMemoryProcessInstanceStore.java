package com.aipersimmon.ddd.processmanager.engine.store;

import com.aipersimmon.ddd.processmanager.model.DefinitionVersion;
import com.aipersimmon.ddd.processmanager.model.ProcessBusinessKey;
import com.aipersimmon.ddd.processmanager.model.ProcessInstanceId;
import com.aipersimmon.ddd.processmanager.model.ProcessLifecycle;
import com.aipersimmon.ddd.processmanager.model.ProcessRef;
import com.aipersimmon.ddd.processmanager.model.ProcessRevision;
import com.aipersimmon.ddd.processmanager.model.ProcessStep;
import com.aipersimmon.ddd.processmanager.model.ProcessType;
import com.aipersimmon.ddd.processmanager.model.StateSchemaVersion;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A {@link ProcessInstanceStore} over a map.
 *
 * <p>The one behaviour it keeps faithfully is the optimistic revision check: {@link
 * #updateSnapshot} applies only when the row's revision is still the one the caller read, and
 * returns 0 otherwise, so the engine's "exactly once per input" advance really has to contend with
 * a concurrent write rather than being handed a store that always says yes. A duplicate insert
 * raises the same {@code DuplicateKeyException} a unique index would, because mapping that to a
 * concurrent-transition failure is a decision the engine makes and therefore has to be able to see.
 */
public final class InMemoryProcessInstanceStore implements ProcessInstanceStore, Snapshottable {

  private final Map<String, ProcessInstanceRow> rows = new LinkedHashMap<>();
  private final Map<String, Instant> touchedAt = new HashMap<>();

  @Override
  public Optional<ProcessInstanceRow> find(ProcessInstanceId instanceId) {
    return Optional.ofNullable(rows.get(instanceId.value()));
  }

  @Override
  public Optional<ProcessInstanceRow> findForUpdate(ProcessInstanceId instanceId) {
    return find(instanceId);
  }

  @Override
  public Optional<ProcessInstanceRow> findByBusinessKey(
      String tenantId, ProcessType processType, ProcessBusinessKey businessKey) {
    return rows.values().stream()
        .filter(row -> row.tenantId().equals(tenantId))
        .filter(row -> row.ref().processType().equals(processType))
        .filter(row -> row.ref().businessKey().equals(businessKey))
        .findFirst();
  }

  @Override
  public Optional<ProcessInstanceRow> readByBusinessKey(
      String tenantId, ProcessType processType, ProcessBusinessKey businessKey) {
    return findByBusinessKey(tenantId, processType, businessKey);
  }

  @Override
  public void insert(ProcessInstanceRow row, Instant now) {
    if (rows.containsKey(row.ref().instanceId().value())
        || findByBusinessKey(row.tenantId(), row.ref().processType(), row.ref().businessKey())
            .isPresent()) {
      throw new org.springframework.dao.DuplicateKeyException(
          "instance already exists for " + row.ref().businessKey().value());
    }
    rows.put(row.ref().instanceId().value(), row);
    touchedAt.put(row.ref().instanceId().value(), now);
  }

  @Override
  public int updateSnapshot(ProcessInstanceRow row, ProcessRevision expectedRevision, Instant now) {
    ProcessInstanceRow current = rows.get(row.ref().instanceId().value());
    if (current == null || !current.revision().equals(expectedRevision)) {
      // What the real UPDATE ... WHERE revision = ? returns when someone else got there first.
      return 0;
    }
    rows.put(row.ref().instanceId().value(), row);
    touchedAt.put(row.ref().instanceId().value(), now);
    return 1;
  }

  @Override
  public void suspend(
      ProcessInstanceId instanceId,
      ProcessLifecycle resumeLifecycle,
      String reason,
      String source,
      String workId,
      Instant now) {
    ProcessInstanceRow current = rows.get(instanceId.value());
    if (current == null) {
      return;
    }
    rows.put(
        instanceId.value(),
        withLifecycle(current, ProcessLifecycle.SUSPENDED, Optional.of(resumeLifecycle), reason));
    suspensionSources.put(instanceId.value(), source);
    touchedAt.put(instanceId.value(), now);
  }

  @Override
  public void resume(ProcessInstanceId instanceId, ProcessLifecycle toLifecycle, Instant now) {
    ProcessInstanceRow current = rows.get(instanceId.value());
    if (current == null) {
      return;
    }
    rows.put(instanceId.value(), withLifecycle(current, toLifecycle, Optional.empty(), null));
    suspensionSources.remove(instanceId.value());
    touchedAt.put(instanceId.value(), now);
  }

  private final Map<String, String> suspensionSources = new HashMap<>();

  @Override
  public Map<String, Long> countSuspendedBySource() {
    Map<String, Long> bySource = new LinkedHashMap<>();
    rows.values().stream()
        .filter(row -> row.lifecycle() == ProcessLifecycle.SUSPENDED)
        .forEach(
            row ->
                bySource.merge(
                    suspensionSources.getOrDefault(row.ref().instanceId().value(), "UNKNOWN"),
                    1L,
                    Long::sum));
    return bySource;
  }

  @Override
  public long countStuck(Instant updatedBefore) {
    return findStuck(updatedBefore, Integer.MAX_VALUE).size();
  }

  @Override
  public List<ProcessInstanceRow> search(ProcessInstanceCriteria criteria, int limit, int offset) {
    return rows.values().stream().skip(offset).limit(limit).toList();
  }

  @Override
  public List<ProcessInstanceRow> findStuck(Instant updatedBefore, int limit) {
    List<ProcessInstanceRow> stuck = new ArrayList<>();
    for (ProcessInstanceRow row : rows.values()) {
      Instant touched = touchedAt.get(row.ref().instanceId().value());
      if (!row.lifecycle().isTerminal() && touched != null && touched.isBefore(updatedBefore)) {
        stuck.add(row);
      }
    }
    return stuck.stream().limit(limit).toList();
  }

  @Override
  public List<VersionRef> distinctVersionsInUse() {
    return rows.values().stream()
        .filter(row -> !row.lifecycle().isTerminal())
        .map(
            row ->
                new VersionRef(
                    row.ref().processType(), row.definitionVersion(), row.stateSchemaVersion()))
        .distinct()
        .toList();
  }

  private static ProcessInstanceRow withLifecycle(
      ProcessInstanceRow row,
      ProcessLifecycle lifecycle,
      Optional<ProcessLifecycle> resumeLifecycle,
      String reason) {
    return new ProcessInstanceRow(
        row.tenantId(),
        row.ref(),
        row.definitionVersion(),
        row.stateSchemaVersion(),
        lifecycle,
        row.step(),
        row.outcome(),
        row.revision(),
        row.statePayloadType(),
        row.statePayload(),
        resumeLifecycle,
        Optional.ofNullable(reason));
  }

  private record Saved(
      Map<String, ProcessInstanceRow> rows,
      Map<String, Instant> touchedAt,
      Map<String, String> suspensionSources) {}

  @Override
  public Object snapshot() {
    return new Saved(
        new LinkedHashMap<>(rows), new HashMap<>(touchedAt), new HashMap<>(suspensionSources));
  }

  @Override
  public void restore(Object snapshot) {
    Saved saved = (Saved) snapshot;
    Map<String, ProcessInstanceRow> survivors = new LinkedHashMap<>();
    committedElsewhere.forEach(
        id -> {
          ProcessInstanceRow row = rows.get(id);
          if (row != null) {
            survivors.put(id, row);
          }
        });
    rows.clear();
    rows.putAll(saved.rows());
    rows.putAll(survivors);
    touchedAt.clear();
    touchedAt.putAll(saved.touchedAt());
    suspensionSources.clear();
    suspensionSources.putAll(saved.suspensionSources());
  }

  // --- what a test needs ------------------------------------------------------------------------

  /** Puts a running instance in the table, bypassing the engine. */
  public ProcessInstanceRow given(ProcessInstanceId instanceId, ProcessLifecycle lifecycle) {
    ProcessInstanceRow row =
        new ProcessInstanceRow(
            "acme",
            new ProcessRef(
                instanceId, new ProcessType("Ordering"), new ProcessBusinessKey("order-1")),
            DefinitionVersion.INITIAL,
            StateSchemaVersion.INITIAL,
            lifecycle,
            new ProcessStep("awaiting-payment"),
            Optional.empty(),
            ProcessRevision.initial(),
            "sample.state",
            "{}".getBytes(StandardCharsets.UTF_8),
            Optional.empty(),
            Optional.empty());
    rows.put(instanceId.value(), row);
    touchedAt.put(instanceId.value(), Instant.EPOCH);
    return row;
  }

  public ProcessInstanceRow row(ProcessInstanceId instanceId) {
    return find(instanceId).orElseThrow();
  }

  public String suspensionSourceOf(ProcessInstanceId instanceId) {
    return suspensionSources.get(instanceId.value());
  }

  public void touch(ProcessInstanceId instanceId, Instant at) {
    touchedAt.put(instanceId.value(), at);
  }

  private final java.util.Set<String> committedElsewhere = new java.util.HashSet<>();

  /**
   * Advance an instance's snapshot on behalf of another transaction that has already committed, so
   * this transaction's rollback leaves the new revision standing.
   *
   * <p>Without it a lost revision race cannot be posed: an in-memory rollback would take the
   * winner's write back out along with the loser's, so the retry would re-read the very revision it
   * started from and succeed — the optimistic guard would look exercised while never actually being
   * contended. What survives a rollback is exactly what another transaction committed.
   *
   * @return the revision the instance now carries
   */
  public ProcessRevision advancedElsewhere(ProcessInstanceId instanceId, Instant now) {
    ProcessInstanceRow current = row(instanceId);
    ProcessRevision next = current.revision().next();
    rows.put(instanceId.value(), withRevision(current, next));
    touchedAt.put(instanceId.value(), now);
    committedElsewhere.add(instanceId.value());
    return next;
  }

  private static ProcessInstanceRow withRevision(ProcessInstanceRow row, ProcessRevision revision) {
    return new ProcessInstanceRow(
        row.tenantId(),
        row.ref(),
        row.definitionVersion(),
        row.stateSchemaVersion(),
        row.lifecycle(),
        row.step(),
        row.outcome(),
        revision,
        row.statePayloadType(),
        row.statePayload(),
        row.resumeLifecycle(),
        row.suspensionReason());
  }
}
