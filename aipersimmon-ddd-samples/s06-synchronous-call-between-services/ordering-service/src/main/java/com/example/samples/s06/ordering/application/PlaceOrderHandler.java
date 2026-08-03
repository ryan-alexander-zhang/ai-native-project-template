package com.example.samples.s06.ordering.application;

import com.aipersimmon.ddd.core.id.IdGenerator;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.samples.s06.ordering.domain.Order;
import com.example.samples.s06.ordering.domain.OrderId;
import com.example.samples.s06.ordering.domain.Orders;
import org.springframework.stereotype.Component;

/**
 * The use case, and it is boring — which is the result the precheck buys.
 *
 * <p>By the time this runs the risk answer is in and approved, so there is no branch, no client, no
 * timeout and no partially-committed state to reason about. The handler does not even know a second
 * service is involved.
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
    orders.save(Order.place(id, command.customerId(), command.amountCents()));
    return id.value();
  }
}
