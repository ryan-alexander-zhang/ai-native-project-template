package com.example.samples.s19.ordering.application;

import com.aipersimmon.ddd.core.id.IdGenerator;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.samples.s19.ordering.domain.Order;
import com.example.samples.s19.ordering.domain.OrderId;
import com.example.samples.s19.ordering.domain.Orders;
import org.springframework.stereotype.Component;

/** The unscreened path. Same aggregate, same invariant, no prechecks. */
@Component
class PlaceOrderInternallyHandler implements CommandHandler<PlaceOrderInternally, String> {

  private final Orders orders;
  private final IdGenerator idGenerator;

  PlaceOrderInternallyHandler(Orders orders, IdGenerator idGenerator) {
    this.orders = orders;
    this.idGenerator = idGenerator;
  }

  @Override
  public String handle(PlaceOrderInternally command, CommandContext context) {
    OrderId id = new OrderId(idGenerator.newId());
    orders.save(Order.place(id, command.customerId(), command.quantity()));
    return id.value();
  }
}
