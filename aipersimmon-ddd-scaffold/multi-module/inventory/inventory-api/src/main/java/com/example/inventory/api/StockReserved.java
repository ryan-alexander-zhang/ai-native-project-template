package com.example.inventory.api;

import com.aipersimmon.ddd.integration.EventType;
import com.aipersimmon.ddd.integration.IntegrationEvent;

/**
 * Integration event published when stock for an order has been reserved — the
 * inventory context's cross-context contract. It carries the order id so the
 * originating context can react, plus the {@code reservationId} handle the reacting
 * saga must keep in order to release the very same reservation later (idempotently)
 * should the flow have to compensate.
 */
@EventType(name = "com.example.inventory.StockReserved", version = 1)
public record StockReserved(String orderId, String reservationId) implements IntegrationEvent {

    @Override
    public String subject() {
        return orderId();
    }
}
