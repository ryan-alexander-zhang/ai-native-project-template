package com.example.samples.s23.ordering.application;

import com.aipersimmon.ddd.application.IntegrationEvents;
import com.aipersimmon.ddd.core.id.IdGenerator;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.samples.s23.ordering.api.OrderPlaced;
import com.example.samples.s23.ordering.domain.Order;
import com.example.samples.s23.ordering.domain.OrderId;
import com.example.samples.s23.ordering.domain.Orders;
import com.example.samples.s23.ordering.domain.ShipTo;
import org.springframework.stereotype.Component;

/**
 * Places an order, and decides its handling by the same rule the backfill uses.
 *
 * <p>"The same rule" is a claim a test checks rather than a comment: a backfilled legacy row and a freshly
 * placed order with identical inputs get identical handling. That equality is the only thing standing
 * between a schema migration and two divergent definitions of the same column.
 *
 * <p>Note that the published event flattens the address back into one string. The contract predates the
 * split and is unaffected by it — see {@link OrderPlaced}.
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
    Order order =
        Order.place(
            id,
            command.customerId(),
            command.sku(),
            command.quantity(),
            new ShipTo(command.street(), command.city()));
    orders.save(order);
    integrationEvents.publish(
        new OrderPlaced(
            id.value(),
            command.customerId(),
            command.sku(),
            command.quantity(),
            command.street() + ", " + command.city()),
        context);
    return id.value();
  }
}
