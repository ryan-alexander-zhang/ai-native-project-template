package com.example.samples.s18.ordering.application;

import com.aipersimmon.ddd.application.IntegrationEvents;
import com.example.samples.s18.ordering.api.OrderPlaced;
import com.aipersimmon.ddd.core.id.IdGenerator;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.samples.s18.ordering.domain.Order;
import com.example.samples.s18.ordering.domain.OrderId;
import com.example.samples.s18.ordering.domain.Orders;
import org.springframework.stereotype.Component;

/**
 * Depends on two ports, which is what makes it worth a unit test: an in-memory {@code Orders} and the
 * library's {@code RecordingIntegrationEvents} answer "did it save, and did it announce" in
 * milliseconds, with no context to start.
 */
@Component
public class PlaceOrderHandler implements CommandHandler<PlaceOrder, String> {

  private final Orders orders;
  private final IntegrationEvents integrationEvents;
  private final IdGenerator idGenerator;

  public PlaceOrderHandler(
      Orders orders, IntegrationEvents integrationEvents, IdGenerator idGenerator) {
    this.orders = orders;
    this.integrationEvents = integrationEvents;
    this.idGenerator = idGenerator;
  }

  @Override
  public String handle(PlaceOrder command, CommandContext context) {
    OrderId id = new OrderId(idGenerator.newId());
    orders.save(Order.place(id, command.customerId(), command.amountCents()));
    integrationEvents.publish(
        new OrderPlaced(id.value(), command.customerId(), command.amountCents()), context);
    return id.value();
  }
}
