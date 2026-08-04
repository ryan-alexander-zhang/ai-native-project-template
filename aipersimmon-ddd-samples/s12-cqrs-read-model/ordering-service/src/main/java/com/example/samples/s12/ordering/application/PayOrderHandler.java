package com.example.samples.s12.ordering.application;

import com.aipersimmon.ddd.application.EntityNotFoundException;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.samples.s12.ordering.domain.Order;
import com.example.samples.s12.ordering.domain.OrderId;
import com.example.samples.s12.ordering.domain.OrderingErrorCode;
import com.example.samples.s12.ordering.domain.Orders;
import java.time.Clock;
import org.springframework.stereotype.Component;

/** Pay the order. The projection updates itself from the event; nothing here mentions it. */
@Component
class PayOrderHandler implements CommandHandler<PayOrder, Void> {

  private final Orders orders;
  private final Clock clock;

  PayOrderHandler(Orders orders, Clock clock) {
    this.orders = orders;
    this.clock = clock;
  }

  @Override
  public Void handle(PayOrder command, CommandContext context) {
    OrderId id = new OrderId(command.orderId());
    Order order =
        orders
            .find(id)
            .orElseThrow(
                () ->
                    new EntityNotFoundException(
                        OrderingErrorCode.ORDER_NOT_FOUND, "no order " + command.orderId()));
    if (order.markPaid(clock.instant())) {
      orders.save(order);
    }
    return null;
  }
}
