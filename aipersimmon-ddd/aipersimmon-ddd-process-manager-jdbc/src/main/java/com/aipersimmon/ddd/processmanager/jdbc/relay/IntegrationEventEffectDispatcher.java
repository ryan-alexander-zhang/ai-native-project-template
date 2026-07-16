package com.aipersimmon.ddd.processmanager.jdbc.relay;

import com.aipersimmon.ddd.application.IntegrationEvents;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.integration.IntegrationEvent;
import com.aipersimmon.ddd.processmanager.effect.ProcessEffectKind;

/**
 * Delivers a {@code PublishIntegrationEvent} effect through {@link IntegrationEvents},
 * which stamps the causal metadata from the reconstructed context onto the outbound
 * envelope. Used to reach a bounded context deployed as a separate service; its own inbox
 * dedupes redeliveries by the event id.
 */
public final class IntegrationEventEffectDispatcher implements ProcessEffectDispatcher {

    private final IntegrationEvents integrationEvents;

    public IntegrationEventEffectDispatcher(IntegrationEvents integrationEvents) {
        this.integrationEvents = integrationEvents;
    }

    @Override
    public ProcessEffectKind kind() {
        return ProcessEffectKind.PUBLISH_INTEGRATION_EVENT;
    }

    @Override
    public void dispatch(DecodedProcessEffect effect, CommandContext context) {
        integrationEvents.publish((IntegrationEvent) effect.payload(), context);
    }
}
