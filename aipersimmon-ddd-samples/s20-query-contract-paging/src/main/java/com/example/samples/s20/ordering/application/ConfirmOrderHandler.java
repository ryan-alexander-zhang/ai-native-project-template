package com.example.samples.s20.ordering.application;

import com.aipersimmon.ddd.application.EntityNotFoundException;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.samples.s20.ordering.domain.Order;
import com.example.samples.s20.ordering.domain.OrderId;
import com.example.samples.s20.ordering.domain.OrderingErrorCode;
import com.example.samples.s20.ordering.domain.Orders;
import org.springframework.stereotype.Component;

/** Load, decide, save — the write path this sample needs and nothing more. */
@Component
class ConfirmOrderHandler implements CommandHandler<ConfirmOrder, Void> {

  private final Orders orders;

  ConfirmOrderHandler(Orders orders) {
    this.orders = orders;
  }

  @Override
  public Void handle(ConfirmOrder command, CommandContext context) {
    Order order =
        orders
            .findById(new OrderId(command.orderId()))
            .orElseThrow(
                () ->
                    new EntityNotFoundException(
                        OrderingErrorCode.ORDER_NOT_FOUND,
                        "order " + command.orderId() + " not found"));
    order.confirm();
    orders.save(order);
    return null;
  }
}
