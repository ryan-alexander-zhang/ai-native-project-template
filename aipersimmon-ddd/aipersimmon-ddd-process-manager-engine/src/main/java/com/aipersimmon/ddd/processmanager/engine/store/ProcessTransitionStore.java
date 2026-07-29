package com.aipersimmon.ddd.processmanager.engine.store;

import com.aipersimmon.ddd.processmanager.model.ProcessInstanceId;
import com.aipersimmon.ddd.processmanager.model.ProcessLifecycle;
import com.aipersimmon.ddd.processmanager.model.ProcessStep;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Appends to the append-only transition log and answers the process-level dedup lookup by {@code
 * (instance_id, input_message_id)}. What a row records — the input, the decision, the lifecycle and
 * step it moved between — is never overwritten; the single exception is the replay marker of a
 * parked input ({@link #markParkedReplayed}), which is that input's disposition rather than a
 * decision. {@link #append} maps a unique-constraint violation on the dedup key to {@link
 * ConcurrentTransitionException} so the runtime can retry it store-neutrally.
 */
public interface ProcessTransitionStore {

  Optional<String> findTransitionIdByInput(ProcessInstanceId instanceId, String inputMessageId);

  Optional<String> findLatestTransitionId(ProcessInstanceId instanceId);

  void append(ProcessTransitionInsert transition, Instant now);

  void appendOperator(
      String tenantId,
      String transitionId,
      ProcessInstanceId instanceId,
      ProcessLifecycle fromLifecycle,
      ProcessLifecycle toLifecycle,
      ProcessStep fromStep,
      ProcessStep toStep,
      String kind,
      String operator,
      String reason,
      Instant now);

  List<ProcessTransitionView> timeline(ProcessInstanceId instanceId);

  /**
   * The inputs parked on an instance that have not been handed back to the runtime yet, in arrival
   * order.
   *
   * <p>These rows are the durable replay queue: an input parked while the instance was suspended is
   * owed exactly one replay, and until {@link #markParkedReplayed} records that it happened, the
   * debt survives a crash. Order is the per-instance {@code transition_seq}, not a wall-clock
   * stamp.
   */
  List<ParkedInput> findUnreplayedParkedInputs(ProcessInstanceId instanceId);

  /**
   * Record that a parked input has been replayed, so it leaves the replay queue.
   *
   * <p>This is the one field of a transition row that is ever written after insert, and it says
   * nothing about the decision the row records — only what became of the input it parked. It is
   * written after the replay's own transaction committed, so a crash in between merely leaves the
   * input in the queue; the replay itself is idempotent, deduped by {@code UNIQUE(instance_id,
   * input_message_id)} on the replay transition.
   *
   * @return the number of rows marked: 1 normally, 0 if another worker got there first
   */
  int markParkedReplayed(ProcessInstanceId instanceId, String inputMessageId, Instant now);

  /**
   * The active instances that still owe a parked-input replay, oldest debt first — the work list of
   * the parked-input worker. An instance that is suspended or ended is not offered: a suspended one
   * would only re-park the input, and an ended one can no longer be advanced.
   */
  List<ProcessInstanceId> findInstancesOwedParkedReplay(int limit);
}
