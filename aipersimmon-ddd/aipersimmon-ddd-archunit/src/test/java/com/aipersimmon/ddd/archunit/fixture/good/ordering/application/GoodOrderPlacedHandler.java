package com.aipersimmon.ddd.archunit.fixture.good.ordering.application;

import com.aipersimmon.ddd.archunit.fixture.good.ordering.domain.GoodOrderPlaced;
import org.springframework.context.event.EventListener;

/**
 * Well-placed domain-event subscriber: it lives in the application layer and reacts
 * to the context's own domain event, satisfying
 * {@code domainEventListenersShouldResideInApplicationOrDomain}.
 */
public class GoodOrderPlacedHandler {

    @EventListener
    public void on(GoodOrderPlaced event) {
        // start a use case / process from the placed order — omitted
    }
}
