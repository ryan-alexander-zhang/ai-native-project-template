package com.example.contracts;

import com.aipersimmon.ddd.integration.EventType;
import com.aipersimmon.ddd.integration.IntegrationEvent;

/**
 * Integration event published when stock for an order has been reserved — the
 * inventory service's success outcome. Carries the order id so the ordering
 * service's saga can confirm the order.
 */
@EventType(name = "com.example.inventory.StockReserved", version = 1)
public record StockReserved(String orderId) implements IntegrationEvent {

    @Override
    public String subject() {
        return orderId();
    }
}
