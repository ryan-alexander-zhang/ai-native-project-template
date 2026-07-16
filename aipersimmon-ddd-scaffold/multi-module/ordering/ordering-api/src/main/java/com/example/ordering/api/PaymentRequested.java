package com.example.ordering.api;

import com.aipersimmon.ddd.integration.EventType;
import com.aipersimmon.ddd.integration.IntegrationEvent;

/**
 * Integration event asking the payment context to charge for an order — the ordering context's
 * cross-context contract for the payment step of fulfilment. The saga emits it once stock is
 * reserved; the payment context reacts and answers with its own {@code PaymentAuthorized} or
 * {@code PaymentDeclined}. It carries the amount to charge in minor units plus its currency.
 */
@EventType(name = "com.example.ordering.PaymentRequested", version = 1)
public record PaymentRequested(String orderId, long amountMinor, String currency) implements IntegrationEvent {

    @Override
    public String subject() {
        return orderId();
    }
}
