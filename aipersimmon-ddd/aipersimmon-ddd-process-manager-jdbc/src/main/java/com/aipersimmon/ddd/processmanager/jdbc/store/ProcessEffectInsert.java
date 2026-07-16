package com.aipersimmon.ddd.processmanager.jdbc.store;

import com.aipersimmon.ddd.processmanager.effect.ProcessEffectKind;
import com.aipersimmon.ddd.processmanager.model.ProcessInstanceId;

/**
 * One staged command/event effect to persist as {@code PENDING}. Its {@code messageId}
 * equals its {@code effectId} — the durable identity minted at creation and replayed
 * verbatim by the relay (design-00004 §3.5; decision-00016). Correlation/causation are
 * derived from the input that produced it.
 */
public record ProcessEffectInsert(
        String effectId,
        ProcessInstanceId instanceId,
        String transitionId,
        int effectIndex,
        ProcessEffectKind kind,
        String payloadType,
        int payloadVersion,
        byte[] payload,
        String messageId,
        String correlationId,
        String causationId,
        String traceId) {

    public ProcessEffectInsert {
        payload = payload.clone();
    }
}
