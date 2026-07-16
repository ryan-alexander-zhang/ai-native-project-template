package com.example.payment.api;

import com.aipersimmon.ddd.integration.EventType;
import com.aipersimmon.ddd.integration.IntegrationEvent;

/**
 * Integration event published when a charge for an order was declined — the payment context's
 * cross-context contract for a failed payment. It carries a stable machine-readable {@code code}
 * and a human-readable {@code reason}, so the ordering saga can compensate (release stock, then
 * cancel) and translate the decline into its own cancellation reason.
 */
@EventType(name = "com.example.payment.PaymentDeclined", version = 1)
public record PaymentDeclined(String orderId, String code, String reason) implements IntegrationEvent {

    @Override
    public String subject() {
        return orderId();
    }
}
