package com.acme.samples.s2.ordering.application.order;

import com.acme.samples.s2.ordering.api.IntegrationEvent;

/**
 * Driven port: hand off an integration event for reliable delivery. The
 * infrastructure adapter writes it to the transactional outbox (topic chosen by
 * event type) in the same transaction as the aggregate change. Replaces the
 * single-event OrderPlacedPublisher now that Ordering emits more than one
 * integration event (OrderPlaced, OrderCancelled).
 */
public interface IntegrationEvents {
    void publish(IntegrationEvent event);
}
