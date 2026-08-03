package com.example.ordering.application.order;

import com.aipersimmon.ddd.application.EntityNotFoundException;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.ordering.domain.order.Order;
import com.example.ordering.domain.order.OrderId;
import com.example.ordering.domain.order.OrderStatus;
import com.example.ordering.domain.order.Orders;
import com.example.ordering.domain.shared.OrderingErrorCode;
import org.springframework.stereotype.Component;

/**
 * Handles {@link ConfirmOrder}: drives the aggregate's state machine, then publishes its events.
 *
 * <p>Sent by the fulfilment process manager, so it arrives at-least-once: a redelivery finding the
 * order already {@code CONFIRMED} — or {@code SHIPPED}, which an order only reaches through
 * confirmation — is its own earlier success, not an error. Both are no-ops for the same reason
 * {@link BeginFulfilmentHandler} tolerates its landed state: letting the aggregate's transition
 * table refuse the duplicate would turn every redelivery into a poison effect the relay retries
 * until it dead-letters. Anything else is a genuine wiring error and is left to the aggregate to
 * refuse.
 */
@Component
public class ConfirmOrderHandler implements CommandHandler<ConfirmOrder, Void> {

  private final Orders orders;

  public ConfirmOrderHandler(Orders orders) {
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
                        OrderingErrorCode.ORDER_NOT_FOUND, "unknown order: " + command.orderId()));

    if (order.status() == OrderStatus.CONFIRMED || order.status() == OrderStatus.SHIPPED) {
      return null;
    }

    order.confirm();

    orders.save(order);
    return null;
  }
}
