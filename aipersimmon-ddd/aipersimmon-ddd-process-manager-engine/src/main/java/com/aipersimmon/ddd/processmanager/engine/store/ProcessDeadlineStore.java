package com.aipersimmon.ddd.processmanager.engine.store;

import com.aipersimmon.ddd.processmanager.model.DeadlineName;
import com.aipersimmon.ddd.processmanager.model.ProcessInstanceId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Persists scheduled deadlines and drives their fire lifecycle. Rescheduling a name bumps its
 * generation so a stale generation firing late is a no-op; the mark/retry/dead/cancel transitions
 * are fenced by the lease token <em>and</em> by the {@code IN_FLIGHT} status, so a worker that lost
 * a race cannot return a settled deadline to the queue.
 */
public interface ProcessDeadlineStore {

  long nextGeneration(ProcessInstanceId instanceId, DeadlineName name);

  long currentGeneration(ProcessInstanceId instanceId, DeadlineName name);

  void schedule(ProcessDeadlineInsert deadline, Instant now);

  void cancelCurrent(ProcessInstanceId instanceId, DeadlineName name, Instant now);

  /**
   * Cancel every still-live deadline of an instance — {@code PENDING} and already-claimed {@code
   * IN_FLIGHT} — because the instance has ended and no timer of it can ever advance anything again.
   *
   * <p>Covering {@code IN_FLIGHT} is the same fence {@link #cancelCurrent} relies on: the deadline
   * worker re-reads the status under the row lock before firing, so a cancel that lands first turns
   * the fire into an auditable no-op. Leaving those rows live would strand them, since the claim
   * query only offers deadlines of active instances and would never reclaim them.
   *
   * @return the number of deadlines cancelled
   */
  int cancelLive(ProcessInstanceId instanceId, Instant now);

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
