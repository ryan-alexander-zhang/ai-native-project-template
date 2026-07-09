package com.acme.samples.s2.ordering.infrastructure.events;

import com.acme.samples.s2.shared.DomainEvent;
import com.acme.samples.s2.shared.DomainEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cross-cutting decorator (analysis-00001 §4): logs each event, then delegates the
 * actual publish. Cross-cutting concerns (log / metrics / tracing) are stacked as
 * decorators so business code and the {@code JustForward} core stay untouched;
 * changing the chain is a wiring change only (see {@link DomainEventsConfig}).
 */
public class LoggingDomainEventPublisher implements DomainEvents {

    private static final Logger log = LoggerFactory.getLogger(LoggingDomainEventPublisher.class);

    private final DomainEvents delegate;

    public LoggingDomainEventPublisher(DomainEvents delegate) {
        this.delegate = delegate;
    }

    @Override
    public void publish(DomainEvent event) {
        log.debug("publishing domain event {}", event.getClass().getSimpleName());
        delegate.publish(event);
    }
}
