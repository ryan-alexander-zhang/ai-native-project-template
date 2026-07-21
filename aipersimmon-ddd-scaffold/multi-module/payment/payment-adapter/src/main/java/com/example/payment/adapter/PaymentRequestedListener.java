package com.example.payment.adapter;

import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.integration.EventEnvelope;
import com.example.ordering.api.PaymentRequested;
import com.example.payment.application.ChargePayment;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Reacts to ordering's {@link PaymentRequested} integration event by sending a {@link
 * ChargePayment} command through the command bus. As the payment context's anti-corruption layer it
 * reads only ordering's published contract and preserves the causing event's context, so the {@code
 * PaymentAuthorized}/{@code PaymentDeclined} it triggers stays correlated to the order.
 */
@Component
public class PaymentRequestedListener {

  private final CommandBus commandBus;

  public PaymentRequestedListener(CommandBus commandBus) {
    this.commandBus = commandBus;
  }

  @EventListener
  public void on(EventEnvelope<PaymentRequested> envelope) {
    PaymentRequested event = envelope.payload();
    commandBus.send(
        new ChargePayment(
            event.orderId(), event.paymentOperationId(),
            event.amountMinor(), event.currency()),
        CommandContext.of(envelope));
  }
}
