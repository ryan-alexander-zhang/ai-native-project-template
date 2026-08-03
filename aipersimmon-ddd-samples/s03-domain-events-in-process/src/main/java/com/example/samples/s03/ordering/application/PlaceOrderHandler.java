package com.example.samples.s03.ordering.application;

import com.aipersimmon.ddd.core.id.IdGenerator;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.samples.s03.ordering.domain.Order;
import com.example.samples.s03.ordering.domain.OrderId;
import com.example.samples.s03.ordering.domain.Orders;
import org.springframework.stereotype.Component;

/**
 * Notice what is missing: no publishing. The handler saves the aggregate and stops — the repository's
 * {@code save} drains the recorded events and publishes them, which is the library's rule and not a
 * convention. A handler that published them itself would be one forgotten line away from losing a fact
 * with no exception, no log and no row to find it in.
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
    OrderId id = new OrderId(idGenerator.newId());
    orders.save(
        Order.place(id, command.customerId(), command.firstOrder(), command.amountCents()));
    return id.value();
  }
}
