package com.example.inventory.api;

import com.aipersimmon.ddd.integration.EventType;
import com.aipersimmon.ddd.integration.IntegrationEvent;

/**
 * Integration event published when a prior stock reservation has been released — the inventory
 * context's cross-context contract for a completed compensation. It is the fact the ordering saga
 * waits for before it cancels an order for a payment decline: only once this event names the
 * order and its {@code reservationId} does the saga hold the stock-release evidence the ordering
 * domain demands.
 */
@EventType(name = "com.example.inventory.StockReleased", version = 1)
public record StockReleased(String orderId, String reservationId) implements IntegrationEvent {

    @Override
    public String subject() {
        return orderId();
    }
}
