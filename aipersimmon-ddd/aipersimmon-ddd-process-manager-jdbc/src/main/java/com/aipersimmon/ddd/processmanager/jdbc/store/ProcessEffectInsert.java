package com.aipersimmon.ddd.processmanager.jdbc.store;

import com.aipersimmon.ddd.processmanager.effect.ProcessEffectKind;
import com.aipersimmon.ddd.processmanager.model.ProcessInstanceId;

/**
 * One staged command/event effect to persist as {@code PENDING}. Its {@code messageId} equals its
 * {@code effectId} — the durable identity minted at creation and replayed verbatim by the relay.
 * Correlation/causation are derived from the input that produced it.
 */
public record ProcessEffectInsert(
    String effectId,
    ProcessInstanceId instanceId,
    String transitionId,
    int effectIndex,
    long seq,
    ProcessEffectKind kind,
    String payloadType,
    int payloadVersion,
    byte[] payload,
    String messageId,
    String correlationId,
    String causationId,
    String traceparent,
    String traceState) {

  public ProcessEffectInsert {
    payload = payload.clone();
  }
}
