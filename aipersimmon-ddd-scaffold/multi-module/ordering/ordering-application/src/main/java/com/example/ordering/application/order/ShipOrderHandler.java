package com.example.ordering.application.order;

import com.aipersimmon.ddd.application.EntityNotFoundException;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.ordering.domain.order.Order;
import com.example.ordering.domain.order.OrderId;
import com.example.ordering.domain.order.Orders;
import com.example.ordering.domain.shared.OrderingErrorCode;
import org.springframework.stereotype.Component;

/**
 * Handles {@link ShipOrder}: loads the order and asks the aggregate to ship. The transition table
 * inside {@code Order} allows {@code CONFIRMED → SHIPPED} and nothing else, so an order that has
 * not been confirmed is refused there rather than here.
 *
 * <p>No credit is released and none should be: shipping is the successful end of the order, and the
 * credit it committed at placement stays committed. That is the difference between this handler and
 * the three that reach {@code Order.cancel}.
 */
@Component
public class ShipOrderHandler implements CommandHandler<ShipOrder, Void> {

  private final Orders orders;

  public ShipOrderHandler(Orders orders) {
    this.orders = orders;
  }

  @Override
  public Void handle(ShipOrder command, CommandContext context) {
    OrderId id = new OrderId(command.orderId());
    Order order =
        orders
            .findById(id)
            .orElseThrow(
                () ->
                    new EntityNotFoundException(
                        OrderingErrorCode.ORDER_NOT_FOUND, "unknown order: " + command.orderId()));

    order.ship();
    orders.save(order);
    return null;
  }
}
