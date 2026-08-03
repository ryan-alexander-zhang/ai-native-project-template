package com.example.samples.s02.ordering.application;

import com.aipersimmon.ddd.core.id.IdGenerator;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.samples.s02.ordering.domain.ClientReference;
import com.example.samples.s02.ordering.domain.Order;
import com.example.samples.s02.ordering.domain.OrderId;
import com.example.samples.s02.ordering.domain.Orders;
import org.springframework.stereotype.Component;

/**
 * The handler is not idempotent and does not try to be: it inserts unconditionally.
 *
 * <p>Two different things keep that safe, and the sample exists to keep them apart. A repeated
 * submission is stopped at the edge by the Idempotency-Key filter, before this runs. A second,
 * genuinely different submission naming the same client reference gets as far as the insert and is
 * refused by the UNIQUE constraint, which the interceptor chain translates into a 409.
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
        Order.place(id, new ClientReference(command.clientReference()), command.amountCents()));
    return id.value();
  }
}
