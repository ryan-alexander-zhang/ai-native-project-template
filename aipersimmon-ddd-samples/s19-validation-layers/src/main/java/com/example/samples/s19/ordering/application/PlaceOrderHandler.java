package com.example.samples.s19.ordering.application;

import com.aipersimmon.ddd.core.id.IdGenerator;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.samples.s19.ordering.domain.Order;
import com.example.samples.s19.ordering.domain.OrderId;
import com.example.samples.s19.ordering.domain.Orders;
import org.springframework.stereotype.Component;

/**
 * Notice how little checking is left here. The shape was checked at the edge and again on the command,
 * the cross-context question was asked before the transaction opened, and the rule about the order's
 * own data belongs to the aggregate. A handler with no validation in it is the sign the other three
 * layers are doing their jobs.
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
    orders.save(Order.place(id, command.customerId(), command.quantity()));
    return id.value();
  }
}
