package com.example.samples.s11.ordering.application;

import com.aipersimmon.ddd.application.EntityNotFoundException;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.samples.s11.ordering.domain.Order;
import com.example.samples.s11.ordering.domain.OrderId;
import com.example.samples.s11.ordering.domain.OrderingErrorCode;
import com.example.samples.s11.ordering.domain.Orders;
import org.springframework.stereotype.Component;

/**
 * Load, decide, save — and note there is nothing here about batches, schedules or timers. The handler
 * cannot tell whether its caller was a timer, an operator or an HTTP request, which is the point of
 * making every entry converge on the command channel: the rule is written once and every entry gets
 * it.
 */
@Component
class CloseExpiredOrderHandler implements CommandHandler<CloseExpiredOrder, Void> {

  private final Orders orders;

  CloseExpiredOrderHandler(Orders orders) {
    this.orders = orders;
  }

  @Override
  public Void handle(CloseExpiredOrder command, CommandContext context) {
    Order order =
        orders
            .findById(new OrderId(command.orderId()))
            .orElseThrow(
                () ->
                    new EntityNotFoundException(
                        OrderingErrorCode.ORDER_NOT_FOUND,
                        "order " + command.orderId() + " not found"));
    order.close();
    orders.save(order);
    return null;
  }
}
