package com.example.samples.s01.ordering.application;

import com.aipersimmon.ddd.application.EntityNotFoundException;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.samples.s01.ordering.domain.Order;
import com.example.samples.s01.ordering.domain.OrderId;
import com.example.samples.s01.ordering.domain.OrderingErrorCode;
import com.example.samples.s01.ordering.domain.Orders;
import org.springframework.stereotype.Component;

/**
 * Load, decide, save. The refusal itself lives in the aggregate: {@code confirm()} consults the
 * transition table, so this handler holds no rule of its own.
 */
@Component
class ConfirmOrderHandler implements CommandHandler<ConfirmOrder, Void> {

  private final Orders orders;

  ConfirmOrderHandler(Orders orders) {
    this.orders = orders;
  }

  @Override
  public Void handle(ConfirmOrder command, CommandContext context) {
    OrderId id = new OrderId(command.orderId());
    Order order =
        orders
            .findById(id)
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
