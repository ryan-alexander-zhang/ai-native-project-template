package com.example.samples.s22.ordering.application;

import com.aipersimmon.ddd.application.IntegrationEvents;
import com.aipersimmon.ddd.core.id.IdGenerator;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.samples.s22.ordering.api.OrderPlaced;
import com.example.samples.s22.ordering.domain.Order;
import com.example.samples.s22.ordering.domain.OrderId;
import com.example.samples.s22.ordering.domain.Orders;
import org.springframework.stereotype.Component;

/**
 * Save the order, announce it. S4 explains why those are one transaction; S22 points at the
 * consequence for whoever gets paged.
 *
 * <p>This handler cannot fail because the broker is down, and that is the point: publication here is
 * a row in the same database. Delivery happens later, on the relay's thread, where no request is
 * waiting and no user is watching. Every failure this sample is about therefore happens somewhere
 * with <em>no caller to return an error to</em> — which is exactly why it needs a table, an endpoint
 * and a metric instead. An architecture that reports failures only by returning them has nothing to
 * say about anything asynchronous.
 */
@Component
class PlaceOrderHandler implements CommandHandler<PlaceOrder, String> {

  private final Orders orders;
  private final IntegrationEvents integrationEvents;
  private final IdGenerator idGenerator;

  PlaceOrderHandler(Orders orders, IntegrationEvents integrationEvents, IdGenerator idGenerator) {
    this.orders = orders;
    this.integrationEvents = integrationEvents;
    this.idGenerator = idGenerator;
  }

  @Override
  public String handle(PlaceOrder command, CommandContext context) {
    OrderId id = new OrderId(idGenerator.newId());
    orders.save(Order.place(id, command.customerId(), command.sku(), command.quantity()));
    integrationEvents.publish(
        new OrderPlaced(id.value(), command.customerId(), command.sku(), command.quantity()),
        context);
    return id.value();
  }
}
