package com.example.samples.s21.ordering.application;

import com.aipersimmon.ddd.application.IntegrationEvents;
import com.aipersimmon.ddd.core.id.IdGenerator;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.samples.s21.ordering.api.OrderPlaced;
import com.example.samples.s21.ordering.domain.Order;
import com.example.samples.s21.ordering.domain.OrderId;
import com.example.samples.s21.ordering.domain.OrderLine;
import com.example.samples.s21.ordering.domain.Orders;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Save the aggregate, publish the fact. Mechanically S4's handler; what S21 adds is that the second
 * line has been rewritten twice and this is the only place in the service that knows it.
 *
 * <p><strong>A revision bump is a one-line change here and a schema decision everywhere else.</strong>
 * Constructing the newest revision is all a publisher does; what makes the bump expensive is the set
 * of consumers that must be able to read what is already on the wire. That cost is not paid in this
 * file, which is exactly why it gets underestimated.
 *
 * <p>Note what the handler does <em>not</em> do: publish more than one revision of the same fact.
 * Dual publishing during a migration looks like generosity to old consumers and is a duplicate-effect
 * bug — the two records are different events with different ids, so no inbox dedups them, and a
 * consumer that still declares the old revision applies the fact twice. The consumer side of this
 * sample has a test that does it on purpose and watches the stock go down twice.
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
    List<OrderLine> lines =
        command.lines().stream().map(line -> new OrderLine(line.sku(), line.quantity())).toList();

    orders.save(Order.place(id, command.customerId(), lines, command.warehouseCode()));

    integrationEvents.publish(
        new OrderPlaced(
            id.value(),
            command.customerId(),
            lines.stream().map(line -> new OrderPlaced.Line(line.sku(), line.quantity())).toList(),
            command.warehouseCode()),
        context);
    return id.value();
  }
}
