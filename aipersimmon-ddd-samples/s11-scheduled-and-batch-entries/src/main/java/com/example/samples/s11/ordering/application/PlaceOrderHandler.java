package com.example.samples.s11.ordering.application;

import com.aipersimmon.ddd.core.id.IdGenerator;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.samples.s11.ordering.domain.Order;
import com.example.samples.s11.ordering.domain.OrderId;
import com.example.samples.s11.ordering.domain.Orders;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Component;

/** Places an order with a deadline. The clock is a bean so a test can decide when "late" is. */
@Component
class PlaceOrderHandler implements CommandHandler<PlaceOrder, String> {

  private final Orders orders;
  private final IdGenerator idGenerator;
  private final Clock clock;

  PlaceOrderHandler(Orders orders, IdGenerator idGenerator, Clock clock) {
    this.orders = orders;
    this.idGenerator = idGenerator;
    this.clock = clock;
  }

  @Override
  public String handle(PlaceOrder command, CommandContext context) {
    OrderId id = new OrderId(idGenerator.newId());
    orders.save(
        Order.place(
            id,
            command.customerId(),
            clock
                .instant()
                .plusSeconds(command.payWithinSeconds())
                .truncatedTo(ChronoUnit.MICROS)));
    return id.value();
  }
}
