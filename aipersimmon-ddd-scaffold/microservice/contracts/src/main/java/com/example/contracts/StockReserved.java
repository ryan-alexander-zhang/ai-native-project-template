package com.example.contracts;

import com.aipersimmon.ddd.integration.IntegrationEvent;

/**
 * Integration event published when stock for an order has been reserved — the
 * inventory service's success outcome. Carries the order id so the ordering
 * service's saga can confirm the order.
 */
public record StockReserved(String orderId) implements IntegrationEvent {
}
