package com.example.howto;

import com.aipersimmon.ddd.integration.IntegrationEvent;

/** Integration event: stock was reserved for an order. Published reliably via the outbox. */
public record StockReserved(String orderId) implements IntegrationEvent {
}
