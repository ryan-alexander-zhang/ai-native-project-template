package com.example.inventory.api;

import com.aipersimmon.ddd.integration.IntegrationEvent;

/**
 * Integration event published when stock for an order has been reserved — the
 * inventory context's cross-context contract. It carries the order id so the
 * originating context can react.
 */
public record StockReserved(String orderId) implements IntegrationEvent {
}
