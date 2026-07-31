package com.example.payment.api;

import com.aipersimmon.ddd.integration.EventType;
import com.aipersimmon.ddd.integration.Externalized;
import com.aipersimmon.ddd.integration.IntegrationEvent;

/**
 * Integration event published when a payment authorization for an order was declined — the payment
 * context's cross-context contract for a failed payment. It carries a stable machine-readable
 * {@code code} and a human-readable {@code reason}, so the ordering process manager can compensate
 * (release stock, then cancel) and translate the decline into its own cancellation reason.
 */
@EventType(name = "com.example.payment.PaymentDeclined", version = 1, source = "/payment")
@Externalized("payment.events")
public record PaymentDeclined(String orderId, String code, String reason)
    implements IntegrationEvent {

  public PaymentDeclined {
    // The code is the machine identity consumers branch on and are entitled to refuse without;
    // the human-readable reason is detail, and consumers demonstrably accept its absence — so
    // the code is required and the reason is not (issue-00143, same bargain as issue-00131).
    Contract.required(orderId, "orderId");
    Contract.required(code, "code");
  }

  @Override
  public String subject() {
    return orderId();
  }
}
