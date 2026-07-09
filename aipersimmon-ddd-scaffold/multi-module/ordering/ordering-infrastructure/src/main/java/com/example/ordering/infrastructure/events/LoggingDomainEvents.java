package com.example.ordering.infrastructure.events;

import com.aipersimmon.ddd.core.event.DomainEvent;
import com.example.ordering.application.order.DomainEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * In-process {@link DomainEvents} implementation that logs each event. A real
 * publisher (in-process dispatch, an outbox to a broker) replaces it without
 * changing the application layer.
 */
@Component
public class LoggingDomainEvents implements DomainEvents {

    private static final Logger log = LoggerFactory.getLogger(LoggingDomainEvents.class);

    @Override
    public void publish(DomainEvent event) {
        log.info("domain event: {}", event);
    }
}
