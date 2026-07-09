package com.example.ordering.application.order;

import com.example.ordering.api.OrderPlaced;

/**
 * Port for publishing the {@link OrderPlaced} integration event — the ordering
 * context's cross-context announcement that an order was placed. The
 * infrastructure layer supplies the transport (here, in-process).
 */
public interface OrderPlacedPublisher {

    void publish(OrderPlaced event);
}
