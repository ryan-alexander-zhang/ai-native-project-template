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
 * Handles {@link CancelOrder}: drives the aggregate's state machine, then publishes its events.
 *
 * <p>This is the compensation entry point — the process manager sends it when stock cannot be
 * reserved, when payment is declined, and when the payment deadline expires — so it is also where
 * most of the credit released in this system comes back (see {@link CustomerCredit}).
 *
 * <p>Being a process-manager effect it arrives at-least-once, so an order found already {@code
 * CANCELLED} is this command's own earlier success and a no-op — the same tolerance {@link
 * BeginFulfilmentHandler} shows its landed state (issue-00130). The no-op must skip the credit
 * release too: the delivery that cancelled the order already released it, and releasing again would
 * hand the customer credit they never committed ({@code Customer.releaseCredit} would refuse only
 * once the balance went negative, which a single duplicate does not reach).
 */
@Component
public class CancelOrderHandler implements CommandHandler<CancelOrder, Void> {

  private final Orders orders;
  private final CustomerCredit credit;

  public CancelOrderHandler(Orders orders, CustomerCredit credit) {
    this.orders = orders;
    this.credit = credit;
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

    if (order.status() == OrderStatus.CANCELLED) {
      return null;
    }

    order.cancel(command.reason());

    orders.save(order);
    credit.releaseFor(order);
    return null;
  }
}
