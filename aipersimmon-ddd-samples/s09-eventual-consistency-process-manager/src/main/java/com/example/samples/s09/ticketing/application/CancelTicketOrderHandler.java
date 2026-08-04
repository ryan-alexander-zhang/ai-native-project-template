package com.example.samples.s09.ticketing.application;

import com.aipersimmon.ddd.application.EntityNotFoundException;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.samples.s09.ticketing.domain.TicketOrder;
import com.example.samples.s09.ticketing.domain.TicketOrderId;
import com.example.samples.s09.ticketing.domain.TicketOrders;
import com.example.samples.s09.ticketing.domain.TicketingErrorCode;
import org.springframework.stereotype.Component;

/** The compensating terminal step: record that the order did not happen, and why. */
@Component
class CancelTicketOrderHandler implements CommandHandler<CancelTicketOrder, Void> {

  private final TicketOrders orders;
  private final TicketingProcess process;

  CancelTicketOrderHandler(TicketOrders orders, TicketingProcess process) {
    this.orders = orders;
    this.process = process;
  }

  @Override
  public Void handle(CancelTicketOrder command, CommandContext context) {
    TicketOrderId id = new TicketOrderId(command.orderId());
    TicketOrder order =
        orders
            .find(id)
            .orElseThrow(
                () ->
                    new EntityNotFoundException(
                        TicketingErrorCode.ORDER_NOT_FOUND, "no order " + command.orderId()));

    order.cancel(command.reason());
    orders.save(order);
    process.orderCancelled(command.orderId(), context);
    return null;
  }
}
