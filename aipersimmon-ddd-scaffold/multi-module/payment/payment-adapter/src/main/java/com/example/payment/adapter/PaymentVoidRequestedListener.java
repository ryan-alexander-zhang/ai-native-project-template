package com.example.payment.adapter;

import com.aipersimmon.ddd.application.InboundEvents;
import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.integration.EventEnvelope;
import com.example.ordering.api.PaymentVoidRequested;
import com.example.payment.application.VoidPayment;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Reacts to ordering's {@link PaymentVoidRequested} integration event by sending a {@link
 * VoidPayment} command through the command bus — the compensation counterpart of {@link
 * PaymentRequestedListener} (issue-00144). Same anti-corruption stance: it reads only ordering's
 * published contract and carries the causing event's context across, so the void stays on the
 * causal chain of the flow that gave the order up.
 */
@Component
public class PaymentVoidRequestedListener {

  private final CommandBus commandBus;

  public PaymentVoidRequestedListener(CommandBus commandBus) {
    this.commandBus = commandBus;
  }

  @EventListener
  public void on(EventEnvelope<PaymentVoidRequested> envelope) {
    PaymentVoidRequested event = envelope.payload();
    commandBus.send(
        new VoidPayment(event.orderId(), event.paymentOperationId()),
        InboundEvents.commandContext(envelope));
  }
}
