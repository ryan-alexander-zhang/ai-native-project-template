package com.aipersimmon.ddd.processmanager.jdbc.store;

import com.aipersimmon.ddd.processmanager.model.DecisionCode;
import com.aipersimmon.ddd.processmanager.model.ProcessInstanceId;
import com.aipersimmon.ddd.processmanager.model.ProcessLifecycle;
import com.aipersimmon.ddd.processmanager.model.ProcessStep;
import java.util.Optional;

/**
 * The data for one appended row of the append-only transition log. {@code fromLifecycle} and {@code
 * fromStep} are empty on the first (start) transition. The encoded input is carried as its logical
 * payload type/version plus base64-persisted bytes.
 */
public record ProcessTransitionInsert(
    String transitionId,
    ProcessInstanceId instanceId,
    String inputMessageId,
    String inputType,
    int inputVersion,
    byte[] inputPayload,
    Optional<ProcessLifecycle> fromLifecycle,
    ProcessLifecycle toLifecycle,
    Optional<ProcessStep> fromStep,
    ProcessStep toStep,
    DecisionCode decisionCode,
    String transitionKind,
    String correlationId) {

  public ProcessTransitionInsert {
    inputPayload = inputPayload.clone();
  }

  public byte[] inputPayload() {
    return inputPayload.clone();
  }
}
