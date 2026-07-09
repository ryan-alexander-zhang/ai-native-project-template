package com.acme.samples.s2.ordering.infrastructure.events;

import com.acme.samples.s2.shared.DomainEvents;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Assembles the pluggable {@link DomainEvents} chain (analysis-00001 §3):
 * decorators wrap the real forwarder. Switching strategy is a change to this
 * assembly only — the application layer depends solely on the {@code DomainEvents}
 * port and is untouched.
 *
 * <p>{@code StoreAndForward} (an outbox for <i>domain</i> events) is deliberately
 * NOT wired here: the {@code OrderPlacedEvent} handler must run in the SAME
 * transaction to write the <i>integration</i>-event outbox atomically
 * (analysis-00005 §3), so domain-event delivery stays synchronous / same-tx.
 */
@Configuration
public class DomainEventsConfig {

    @Bean
    DomainEvents domainEvents(ApplicationEventPublisher publisher) {
        return new LoggingDomainEventPublisher(
                new JustForwardDomainEventPublisher(publisher));
    }
}
