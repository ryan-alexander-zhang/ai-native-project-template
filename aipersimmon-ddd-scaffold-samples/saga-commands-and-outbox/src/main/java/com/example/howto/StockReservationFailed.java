package com.example.howto;

import com.aipersimmon.ddd.integration.IntegrationEvent;

/** Integration event: stock could not be reserved. Published reliably via the outbox. */
public record StockReservationFailed(String orderId, String reason) implements IntegrationEvent {
}
