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
 * Handles {@link BeginFulfilment}: the order stops being merely ready and enters fulfilment.
 *
 * <h2>Why this tolerates two states instead of refusing them</h2>
 *
 * <p>Process-manager effects are dispatched at-least-once, and this one races a customer. Both
 * cases are no-ops rather than failures, and each for its own reason:
 *
 * <ul>
 *   <li><strong>Already {@code FULFILMENT_IN_PROGRESS}</strong> — a redelivery of an effect that
 *       already landed. Letting the aggregate's transition table refuse it would turn every
 *       duplicate delivery into a poison effect the relay retries until it dead-letters.
 *   <li><strong>Already {@code CANCELLED}</strong> — the customer used the self-cancel window while
 *       inventory was reserving. The cancellation wins: it happened when the order was still theirs
 *       to cancel, and reviving it here would silently overrule them. The reserved stock is not
 *       lost by ignoring this — the same {@code OrderCancelled} fact that cancelled the order
 *       reaches the process manager, which compensates by releasing it.
 * </ul>
 *
 * <p>Anything else is a genuine wiring error and is left to the aggregate to refuse.
 */
@Component
public class BeginFulfilmentHandler implements CommandHandler<BeginFulfilment, Void> {

  private final Orders orders;

  public BeginFulfilmentHandler(Orders orders) {
    this.orders = orders;
  }

  @Override
  public Void handle(BeginFulfilment command, CommandContext context) {
    OrderId id = new OrderId(command.orderId());
    Order order =
        orders
            .findById(id)
            .orElseThrow(
                () ->
                    new EntityNotFoundException(
                        OrderingErrorCode.ORDER_NOT_FOUND, "unknown order: " + command.orderId()));

    if (order.status() == OrderStatus.FULFILMENT_IN_PROGRESS
        || order.status() == OrderStatus.CANCELLED) {
      return null;
    }

    order.beginFulfilment();
    orders.save(order);
    return null;
  }
}
