package com.example.ordering.api;

import com.aipersimmon.ddd.integration.EventType;
import com.aipersimmon.ddd.integration.Externalized;
import com.aipersimmon.ddd.integration.IntegrationEvent;

/**
 * Integration event asking the payment context to charge for an order — the ordering context's
 * cross-context contract for the payment step of fulfilment. The saga emits it once stock is
 * reserved; the payment context reacts and answers with its own {@code PaymentAuthorized} or
 * {@code PaymentDeclined}. It carries the amount to charge in minor units plus its currency, and a
 * {@code paymentOperationId} — the business idempotency key the payment context dedupes by, so an
 * at-least-once redelivery of this event charges only once (design-00004 §13.2).
 */
@EventType(name = "com.example.ordering.PaymentRequested", version = 1)
@Externalized("ordering.events")
public record PaymentRequested(String orderId, String paymentOperationId, long amountMinor, String currency)
        implements IntegrationEvent {

    @Override
    public String subject() {
        return orderId();
    }
}
