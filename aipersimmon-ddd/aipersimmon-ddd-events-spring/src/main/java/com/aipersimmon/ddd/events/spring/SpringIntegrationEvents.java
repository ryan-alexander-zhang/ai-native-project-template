package com.aipersimmon.ddd.events.spring;

import com.aipersimmon.ddd.application.IntegrationEvents;
import com.aipersimmon.ddd.integration.IntegrationEvent;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Publishes integration events in process through Spring's
 * {@link ApplicationEventPublisher} — the synchronous, same-thread,
 * same-transaction transport for a modular monolith where producer and consumer
 * share one deployable. Consumers register {@code @EventListener} handlers for the
 * integration-event type.
 *
 * <p>This is the "in-process synchronous" integration transport. For reliable
 * delivery decoupled from the producer's transaction (or across processes), use
 * the outbox instead.
 */
public class SpringIntegrationEvents implements IntegrationEvents {

    private final ApplicationEventPublisher publisher;

    public SpringIntegrationEvents(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public void publish(IntegrationEvent event) {
        publisher.publishEvent(event);
    }
}
