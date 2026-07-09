package com.acme.samples.s2.ordering.application.order;

import com.acme.samples.s2.ordering.api.OrderPlaced;

/**
 * Driven port: hand off the integration event for reliable delivery. The
 * infrastructure adapter writes it to a transactional outbox in the same
 * transaction as the order.
 */
public interface OrderPlacedPublisher {
    void publish(OrderPlaced event);
}
