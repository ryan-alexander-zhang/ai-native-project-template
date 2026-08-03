package com.example.samples.s01.ordering.application;

import com.aipersimmon.ddd.core.id.IdGenerator;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.samples.s01.ordering.domain.Order;
import com.example.samples.s01.ordering.domain.OrderId;
import com.example.samples.s01.ordering.domain.OrderLine;
import com.example.samples.s01.ordering.domain.Orders;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * A concrete class, not a lambda: the bus indexes handlers by the command's exact class, read off the
 * class's own type parameters, and a lambda erases them.
 *
 * <p>No {@code @Transactional} — the bus's transaction interceptor already runs one around this.
 */
@Component
class PlaceOrderHandler implements CommandHandler<PlaceOrder, String> {

  private final Orders orders;
  private final IdGenerator idGenerator;

  PlaceOrderHandler(Orders orders, IdGenerator idGenerator) {
    this.orders = orders;
    this.idGenerator = idGenerator;
  }

  @Override
  public String handle(PlaceOrder command, CommandContext context) {
    // Nothing in the framework mints an aggregate id for you; ids are time-ordered UUIDv7.
    OrderId id = new OrderId(idGenerator.newId());
    Order order = Order.place(id, command.customerId(), lines(command));
    orders.save(order);
    return id.value();
  }

  private static List<OrderLine> lines(PlaceOrder command) {
    return command.lines().stream().map(line -> new OrderLine(line.sku(), line.quantity())).toList();
  }
}
