package com.example.ordering.application.order;

import com.aipersimmon.ddd.application.DomainEvents;
import com.aipersimmon.ddd.application.EntityNotFoundException;
import com.aipersimmon.ddd.application.UseCase;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.ordering.domain.order.Order;
import com.example.ordering.domain.order.OrderId;
import com.example.ordering.domain.order.Orders;
import com.example.ordering.domain.shared.OrderingErrorCode;
import org.springframework.stereotype.Component;

/**
 * Handles {@link ConfirmOrder}: drives the aggregate's state machine, then publishes its events.
 */
@Component
@UseCase
public class ConfirmOrderHandler implements CommandHandler<ConfirmOrder, Void> {

  private final Orders orders;
  private final DomainEvents domainEvents;

  public ConfirmOrderHandler(Orders orders, DomainEvents domainEvents) {
    this.orders = orders;
    this.domainEvents = domainEvents;
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

    order.confirm();

    orders.save(order);
    domainEvents.publishAndClear(order);
    return null;
  }
}
