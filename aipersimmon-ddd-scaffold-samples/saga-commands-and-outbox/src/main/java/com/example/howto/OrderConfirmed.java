package com.example.howto;

import com.aipersimmon.ddd.integration.IntegrationEvent;

/** Integration event: the order was confirmed. Published reliably via the outbox. */
public record OrderConfirmed(String orderId) implements IntegrationEvent {
}
