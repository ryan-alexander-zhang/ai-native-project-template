package com.acme.samples.s2.ordering.infrastructure.events;

import com.acme.samples.s2.shared.DomainEvent;
import com.acme.samples.s2.shared.DomainEvents;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Real publisher (analysis-00001 §3): forwards to Spring's
 * {@link ApplicationEventPublisher} — synchronous, same thread, same transaction.
 * {@code @EventListener} handlers therefore run inline within the caller's
 * transaction.
 */
public class JustForwardDomainEventPublisher implements DomainEvents {

    private final ApplicationEventPublisher publisher;

    public JustForwardDomainEventPublisher(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public void publish(DomainEvent event) {
        publisher.publishEvent(event);
    }
}
