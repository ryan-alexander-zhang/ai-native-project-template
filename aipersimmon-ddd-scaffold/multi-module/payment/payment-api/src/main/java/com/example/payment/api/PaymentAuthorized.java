package com.example.payment.api;

import com.aipersimmon.ddd.integration.EventType;
import com.aipersimmon.ddd.integration.IntegrationEvent;

/**
 * Integration event published when a charge for an order was authorised — the payment context's
 * cross-context contract for a successful payment. The ordering saga reacts by confirming the order.
 */
@EventType(name = "com.example.payment.PaymentAuthorized", version = 1)
public record PaymentAuthorized(String orderId) implements IntegrationEvent {

    @Override
    public String subject() {
        return orderId();
    }
}
