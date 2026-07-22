package com.example.ordering.application.order;

import com.aipersimmon.ddd.application.EntityNotFoundException;
import com.aipersimmon.ddd.application.IntegrationEvents;
import com.aipersimmon.ddd.application.UseCase;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.ordering.api.PaymentRequested;
import com.example.ordering.domain.order.Order;
import com.example.ordering.domain.order.OrderId;
import com.example.ordering.domain.order.Orders;
import com.example.ordering.domain.shared.Money;
import com.example.ordering.domain.shared.OrderingErrorCode;
import org.springframework.stereotype.Component;

/**
 * Handles {@link RequestPayment}: reads the order's total and publishes {@link PaymentRequested} so
 * the payment context can authorize it. This mirrors {@code PlaceOrderHandler}'s pattern of turning
 * a use case into an outbound integration event, keeping that concern out of the process manager.
 */
@Component
@UseCase
public class RequestPaymentHandler implements CommandHandler<RequestPayment, Void> {

  private final Orders orders;
  private final IntegrationEvents integrationEvents;

  public RequestPaymentHandler(Orders orders, IntegrationEvents integrationEvents) {
    this.orders = orders;
    this.integrationEvents = integrationEvents;
  }

  @Override
  public Void handle(RequestPayment command, CommandContext context) {
    Order order =
        orders
            .findById(new OrderId(command.orderId()))
            .orElseThrow(
                () ->
                    new EntityNotFoundException(
                        OrderingErrorCode.ORDER_NOT_FOUND, "unknown order: " + command.orderId()));
    Money total = order.total();
    integrationEvents.publish(
        new PaymentRequested(
            command.orderId(), command.paymentOperationId(),
            total.amountMinor(), total.currency()),
        context);
    return null;
  }
}
