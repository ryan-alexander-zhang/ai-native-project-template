package com.example.ordering.infrastructure.events;

import com.example.ordering.api.OrderPlaced;
import com.example.ordering.application.order.OrderPlacedPublisher;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * In-process transport for the {@link OrderPlaced} integration event: it hands
 * the event to Spring's publisher so listeners in other contexts (running in the
 * same deployable) receive it synchronously. A broker-backed transport with an
 * outbox replaces this without touching the application layer.
 */
@Component
public class SpringOrderPlacedPublisher implements OrderPlacedPublisher {

    private final ApplicationEventPublisher events;

    public SpringOrderPlacedPublisher(ApplicationEventPublisher events) {
        this.events = events;
    }

    @Override
    public void publish(OrderPlaced event) {
        events.publishEvent(event);
    }
}
