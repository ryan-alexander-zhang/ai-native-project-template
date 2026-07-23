package com.aipersimmon.ddd.processmanager.engine.store;

import com.aipersimmon.ddd.processmanager.codec.PayloadType;

/**
 * A parked input awaiting replay after the instance resumes, with the causal context to replay
 * under.
 */
public record ParkedInput(
    String inputMessageId, PayloadType inputType, byte[] inputPayload, String correlationId) {

  public ParkedInput {
    inputPayload = inputPayload.clone();
  }

  @Override
  public byte[] inputPayload() {
    return inputPayload.clone();
  }
}
