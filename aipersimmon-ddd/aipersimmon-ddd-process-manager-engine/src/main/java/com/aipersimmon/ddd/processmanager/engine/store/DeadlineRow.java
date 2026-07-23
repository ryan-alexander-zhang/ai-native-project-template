package com.aipersimmon.ddd.processmanager.engine.store;

import com.aipersimmon.ddd.processmanager.codec.PayloadType;
import com.aipersimmon.ddd.processmanager.model.DeadlineName;
import com.aipersimmon.ddd.processmanager.model.ProcessInstanceId;

/**
 * A claimed deadline loaded for firing: its identity, encoded input, causal context, and attempt
 * count.
 */
public record DeadlineRow(
    String deadlineId,
    ProcessInstanceId instanceId,
    DeadlineName name,
    long generation,
    PayloadType inputType,
    byte[] inputPayload,
    String correlationId,
    String causationId,
    int attempts,
    String traceparent,
    String traceState) {

  public DeadlineRow {
    inputPayload = inputPayload.clone();
  }

  @Override
  public byte[] inputPayload() {
    return inputPayload.clone();
  }
}
