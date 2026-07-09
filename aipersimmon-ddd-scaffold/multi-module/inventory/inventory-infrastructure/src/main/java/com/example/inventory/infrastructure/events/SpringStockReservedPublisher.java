package com.example.inventory.infrastructure.events;

import com.example.inventory.api.StockReserved;
import com.example.inventory.application.stock.StockReservedPublisher;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * In-process transport for the {@link StockReserved} integration event, handing
 * it to Spring's publisher for listeners in other contexts within the same
 * deployable. A broker-backed transport replaces this without touching the
 * application layer.
 */
@Component
public class SpringStockReservedPublisher implements StockReservedPublisher {

    private final ApplicationEventPublisher events;

    public SpringStockReservedPublisher(ApplicationEventPublisher events) {
        this.events = events;
    }

    @Override
    public void publish(StockReserved event) {
        events.publishEvent(event);
    }
}
