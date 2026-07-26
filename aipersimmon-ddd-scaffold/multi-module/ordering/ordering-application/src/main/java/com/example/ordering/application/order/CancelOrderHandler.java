package com.example.ordering.application.order;

import com.aipersimmon.ddd.application.EntityNotFoundException;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.ordering.domain.order.Order;
import com.example.ordering.domain.order.OrderId;
import com.example.ordering.domain.order.Orders;
import com.example.ordering.domain.shared.OrderingErrorCode;
import org.springframework.stereotype.Component;

/** Handles {@link CancelOrder}: drives the aggregate's state machine, then publishes its events. */
@Component
public class CancelOrderHandler implements CommandHandler<CancelOrder, Void> {

  private final Orders orders;

  public CancelOrderHandler(Orders orders) {
    this.orders = orders;
  }

  @Override
  public Void handle(CancelOrder command, CommandContext context) {
    OrderId id = new OrderId(command.orderId());
    Order order =
        orders
            .findById(id)
            .orElseThrow(
                () ->
                    new EntityNotFoundException(
                        OrderingErrorCode.ORDER_NOT_FOUND, "unknown order: " + command.orderId()));

    order.cancel(command.reason());

    orders.save(order);
    return null;
  }
}
