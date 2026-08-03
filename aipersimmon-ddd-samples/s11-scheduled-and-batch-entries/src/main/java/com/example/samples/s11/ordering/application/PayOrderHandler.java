package com.example.samples.s11.ordering.application;

import com.aipersimmon.ddd.application.EntityNotFoundException;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.samples.s11.ordering.domain.Order;
import com.example.samples.s11.ordering.domain.OrderId;
import com.example.samples.s11.ordering.domain.OrderingErrorCode;
import com.example.samples.s11.ordering.domain.Orders;
import org.springframework.stereotype.Component;

/** The same three lines as closing. Two entries, one channel, one rule. */
@Component
class PayOrderHandler implements CommandHandler<PayOrder, Void> {

  private final Orders orders;

  PayOrderHandler(Orders orders) {
    this.orders = orders;
  }

  @Override
  public Void handle(PayOrder command, CommandContext context) {
    Order order =
        orders
            .findById(new OrderId(command.orderId()))
            .orElseThrow(
                () ->
                    new EntityNotFoundException(
                        OrderingErrorCode.ORDER_NOT_FOUND,
                        "order " + command.orderId() + " not found"));
    order.pay();
    orders.save(order);
    return null;
  }
}
