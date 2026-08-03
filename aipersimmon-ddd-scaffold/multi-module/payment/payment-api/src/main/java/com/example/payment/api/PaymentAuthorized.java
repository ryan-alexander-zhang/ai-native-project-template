package com.example.payment.api;

import com.aipersimmon.ddd.integration.EventType;
import com.aipersimmon.ddd.integration.Externalized;
import com.aipersimmon.ddd.integration.IntegrationEvent;

/**
 * Integration event published when a payment for an order was authorised — the payment context's
 * cross-context contract for a successful payment. The ordering process manager reacts by
 * confirming the order.
 */
@EventType(name = "com.example.payment.PaymentAuthorized", version = 1, source = "/payment")
@Externalized("payment.events")
public record PaymentAuthorized(String orderId) implements IntegrationEvent {

  public PaymentAuthorized {
    // The order id is the whole message: an authorization that names no order confirms nothing.
    Contract.required(orderId, "orderId");
  }

  @Override
  public String subject() {
    return orderId();
  }
}
