package com.aipersimmon.ddd.processmanager.engine.store;

import com.aipersimmon.ddd.processmanager.model.DeadlineName;
import com.aipersimmon.ddd.processmanager.model.ProcessInstanceId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Persists scheduled deadlines and drives their fire lifecycle. Rescheduling a name bumps its
 * generation so a stale generation firing late is a no-op; the mark/retry/dead/cancel transitions
 * are fenced by the lease token.
 */
public interface ProcessDeadlineStore {

  long nextGeneration(ProcessInstanceId instanceId, DeadlineName name);

  long currentGeneration(ProcessInstanceId instanceId, DeadlineName name);

  void schedule(ProcessDeadlineInsert deadline, Instant now);

  void cancelCurrent(ProcessInstanceId instanceId, DeadlineName name, Instant now);

  int cancelPending(ProcessInstanceId instanceId, Instant now);

  int cancelClaimed(String deadlineId, String leaseToken, Instant now);

  Optional<DeadlineStatus> statusForUpdate(String deadlineId);

  Optional<DeadlineRow> load(String deadlineId);

  int markFired(String deadlineId, String leaseToken, Instant now);

  int scheduleRetry(
      String deadlineId, String leaseToken, Instant nextAttemptAt, String error, Instant now);

  int markDead(String deadlineId, String leaseToken, String error, Instant now);

  int redrive(String deadlineId, Instant now);

  long countDead();

  long countDead(ProcessInstanceId instanceId);

  Optional<Instant> oldestDuePending(Instant now);

  List<ProcessDeadlineView> byStatus(DeadlineStatus status, int limit);
}
