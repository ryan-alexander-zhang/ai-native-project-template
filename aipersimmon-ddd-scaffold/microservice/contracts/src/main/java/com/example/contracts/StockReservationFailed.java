package com.example.contracts;

import com.aipersimmon.ddd.integration.EventType;
import com.aipersimmon.ddd.integration.IntegrationEvent;

/**
 * Integration event published when stock for an order could not be reserved — the
 * inventory service's failure outcome. Carries the order id and a reason so the
 * ordering service's saga can compensate by cancelling the order.
 */
@EventType(name = "com.example.inventory.StockReservationFailed", version = 1)
public record StockReservationFailed(String orderId, String reason) implements IntegrationEvent {

    @Override
    public String subject() {
        return orderId();
    }
}
