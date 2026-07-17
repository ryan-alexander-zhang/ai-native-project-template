package com.aipersimmon.ddd.processmanager.effect;

import com.aipersimmon.ddd.integration.IntegrationEvent;

/**
 * Effect asking the runtime to publish {@code event} through the outbox. Used to reach
 * a bounded context deployed as a separate service: the target consumes the event and
 * its inbound adapter translates it into a local command. The event's logical
 * type/version comes from its {@code @EventType}; a codec provides the payload
 * serialization.
 *
 * @param event the integration event to publish; non-null, business fields only
 */
public record PublishIntegrationEvent(IntegrationEvent event) implements ProcessEffect {

    public PublishIntegrationEvent {
        if (event == null) {
            throw new IllegalArgumentException("event required");
        }
    }

    @Override
    public ProcessEffectKind kind() {
        return ProcessEffectKind.PUBLISH_INTEGRATION_EVENT;
    }
}
