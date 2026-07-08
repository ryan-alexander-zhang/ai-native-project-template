package com.acme.samples.s3.ordering.app;

import com.acme.samples.s3.ordering.client.OrderPlaced;

/** Driven port: hand the event to the transactional outbox. */
public interface OrderPlacedPublisher {
    void publish(OrderPlaced event);
}
