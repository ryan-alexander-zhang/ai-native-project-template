package com.aipersimmon.ddd.processmanager.engine.store;

import com.aipersimmon.ddd.processmanager.model.DefinitionVersion;
import com.aipersimmon.ddd.processmanager.model.ProcessLifecycle;
import com.aipersimmon.ddd.processmanager.model.ProcessOutcome;
import com.aipersimmon.ddd.processmanager.model.ProcessRef;
import com.aipersimmon.ddd.processmanager.model.ProcessRevision;
import com.aipersimmon.ddd.processmanager.model.ProcessStep;
import com.aipersimmon.ddd.processmanager.model.StateSchemaVersion;
import java.util.Optional;

/**
 * The current snapshot of an instance as read from {@code aipersimmon_process_instance}. The
 * encoded state is exposed as its logical payload type plus raw bytes, which the runtime hands to
 * the state codec to decode; the byte array is defensively copied.
 */
public record ProcessInstanceRow(
    ProcessRef ref,
    DefinitionVersion definitionVersion,
    StateSchemaVersion stateSchemaVersion,
    ProcessLifecycle lifecycle,
    ProcessStep step,
    Optional<ProcessOutcome> outcome,
    ProcessRevision revision,
    String statePayloadType,
    byte[] statePayload,
    Optional<ProcessLifecycle> resumeLifecycle,
    Optional<String> suspensionReason) {

  public ProcessInstanceRow {
    statePayload = statePayload.clone();
  }

  @Override
  public byte[] statePayload() {
    return statePayload.clone();
  }

  /**
   * Fail fast when this row's ref disagrees with the {@code expected} one, so an operator action or
   * a load-boundary check never targets the wrong instance. The same guard is applied by the
   * runtime on handle and by the operations facade.
   */
  public void requireRefMatches(ProcessRef expected) {
    if (!ref().equals(expected)) {
      throw new IllegalArgumentException(
          "process ref mismatch for instance "
              + expected.instanceId().value()
              + ": supplied "
              + expected.processType().value()
              + "/"
              + expected.businessKey().value()
              + " but the stored instance is "
              + ref().processType().value()
              + "/"
              + ref().businessKey().value());
    }
  }
}
