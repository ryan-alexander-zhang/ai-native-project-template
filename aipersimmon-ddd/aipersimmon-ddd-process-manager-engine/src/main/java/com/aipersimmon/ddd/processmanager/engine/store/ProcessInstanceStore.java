package com.aipersimmon.ddd.processmanager.engine.store;

import com.aipersimmon.ddd.processmanager.model.ProcessBusinessKey;
import com.aipersimmon.ddd.processmanager.model.ProcessInstanceId;
import com.aipersimmon.ddd.processmanager.model.ProcessLifecycle;
import com.aipersimmon.ddd.processmanager.model.ProcessRevision;
import com.aipersimmon.ddd.processmanager.model.ProcessType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads and writes the current instance snapshot. A storage backend (JDBC, MyBatis-Plus) implements
 * this port; the engine's runtime, query, operations, and backlog depend only on it. {@link
 * #updateSnapshot} carries the expected revision so a concurrent transition cannot overwrite it
 * (optimistic concurrency); {@code findForUpdate}/{@code findByBusinessKey} take a row lock for the
 * advance transaction.
 */
public interface ProcessInstanceStore {

  Optional<ProcessInstanceRow> find(ProcessInstanceId instanceId);

  Optional<ProcessInstanceRow> findForUpdate(ProcessInstanceId instanceId);

  Optional<ProcessInstanceRow> findByBusinessKey(
      ProcessType processType, ProcessBusinessKey businessKey);

  Optional<ProcessInstanceRow> readByBusinessKey(
      ProcessType processType, ProcessBusinessKey businessKey);

  void insert(ProcessInstanceRow row, Instant now);

  int updateSnapshot(ProcessInstanceRow row, ProcessRevision expectedRevision, Instant now);

  void suspend(
      ProcessInstanceId instanceId,
      ProcessLifecycle resumeLifecycle,
      String reason,
      String source,
      String workId,
      Instant now);

  void resume(ProcessInstanceId instanceId, ProcessLifecycle toLifecycle, Instant now);

  Map<String, Long> countSuspendedBySource();

  long countStuck(Instant updatedBefore);

  List<ProcessInstanceRow> search(ProcessInstanceCriteria criteria, int limit, int offset);

  List<ProcessInstanceRow> findStuck(Instant updatedBefore, int limit);

  List<VersionRef> distinctVersionsInUse();
}
