package com.aipersimmon.ddd.events.spring;

import com.aipersimmon.ddd.application.DomainEvents;
import com.aipersimmon.ddd.core.event.DomainEvent;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Publishes domain events through Spring's {@link ApplicationEventPublisher}, so
 * beans that declare {@code @EventListener} (or {@code @TransactionalEventListener})
 * handlers for a given event type receive it. Delivery is synchronous and runs on
 * the caller's thread and transaction.
 */
public class SpringDomainEvents implements DomainEvents {

    private final ApplicationEventPublisher publisher;

    public SpringDomainEvents(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public void publish(DomainEvent event) {
        publisher.publishEvent(event);
    }
}
