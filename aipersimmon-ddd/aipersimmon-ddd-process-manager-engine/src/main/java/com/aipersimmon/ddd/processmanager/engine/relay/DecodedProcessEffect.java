package com.aipersimmon.ddd.processmanager.jdbc.relay;

import com.aipersimmon.ddd.processmanager.effect.ProcessEffectKind;
import com.aipersimmon.ddd.processmanager.model.ProcessInstanceId;

/**
 * A staged effect whose payload the relay has decoded back to its Java form (a {@code Command} or
 * an {@code IntegrationEvent}), ready for a dispatcher. The relay dispatches it under the
 * reconstructed {@code CommandContext} whose {@code messageId} is this effect's stable id.
 *
 * @param effectId the durable effect id (also the dispatch messageId)
 * @param instanceId the owning instance
 * @param kind the effect kind, for dispatcher routing
 * @param payload the decoded command or integration event
 */
public record DecodedProcessEffect(
    String effectId, ProcessInstanceId instanceId, ProcessEffectKind kind, Object payload) {}
