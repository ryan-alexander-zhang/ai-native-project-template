package com.aipersimmon.ddd.processmanager.engine.store;

import com.aipersimmon.ddd.processmanager.model.ProcessInstanceId;
import com.aipersimmon.ddd.processmanager.model.ProcessLifecycle;
import com.aipersimmon.ddd.processmanager.model.ProcessStep;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Appends to the append-only transition log and answers the process-level dedup lookup by {@code
 * (instance_id, input_message_id)}. A history row is never overwritten. {@link #append} maps a
 * unique-constraint violation on that dedup key to {@link ConcurrentTransitionException} so the
 * runtime can retry it store-neutrally.
 */
public interface ProcessTransitionStore {

  Optional<String> findTransitionIdByInput(ProcessInstanceId instanceId, String inputMessageId);

  Optional<String> findLatestTransitionId(ProcessInstanceId instanceId);

  void append(ProcessTransitionInsert transition, Instant now);

  void appendOperator(
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

  List<ParkedInput> findParkedInputs(ProcessInstanceId instanceId);
}
