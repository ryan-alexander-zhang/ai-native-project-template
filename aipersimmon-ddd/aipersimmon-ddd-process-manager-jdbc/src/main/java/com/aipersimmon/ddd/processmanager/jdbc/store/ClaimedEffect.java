package com.aipersimmon.ddd.processmanager.jdbc.store;

import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.processmanager.codec.PayloadType;
import com.aipersimmon.ddd.processmanager.effect.ProcessEffectKind;
import com.aipersimmon.ddd.processmanager.model.ProcessInstanceId;

/**
 * A staged effect loaded for dispatch: its kind and encoded payload, the reconstructed
 * {@link CommandContext} (built from the persisted identity, so {@code messageId} equals
 * the effect id), and the current attempt count. The relay decodes the payload with a
 * codec, dispatches under this context, then completes the row fenced by its lease token.
 */
public record ClaimedEffect(
        String effectId,
        ProcessInstanceId instanceId,
        ProcessEffectKind kind,
        PayloadType payloadType,
        byte[] payload,
        CommandContext context,
        int attempts) {

    public ClaimedEffect {
        payload = payload.clone();
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }
}
