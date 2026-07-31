package com.example.ordering.application.order;

import com.aipersimmon.ddd.application.IntegrationEvents;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.ordering.api.PaymentVoidRequested;
import org.springframework.stereotype.Component;

/**
 * Handles {@link RequestPaymentVoid}: publishes {@link PaymentVoidRequested} so the payment context
 * can void (or refuse in advance) the named operation. Mirrors {@code RequestStockReleaseHandler} —
 * the outbound-event concern stays in a use-case handler, so the process manager sends only
 * ordering commands. No order lookup: the operation id is the whole message, and the order may
 * legitimately already be cancelled by the time this runs.
 */
@Component
public class RequestPaymentVoidHandler implements CommandHandler<RequestPaymentVoid, Void> {

  private final IntegrationEvents integrationEvents;

  public RequestPaymentVoidHandler(IntegrationEvents integrationEvents) {
    this.integrationEvents = integrationEvents;
  }

  @Override
  public Void handle(RequestPaymentVoid command, CommandContext context) {
    integrationEvents.publish(
        new PaymentVoidRequested(command.orderId(), command.paymentOperationId()), context);
    return null;
  }
}
