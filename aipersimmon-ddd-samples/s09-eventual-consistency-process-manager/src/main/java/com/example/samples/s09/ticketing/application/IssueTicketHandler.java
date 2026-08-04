package com.example.samples.s09.ticketing.application;

import com.aipersimmon.ddd.application.EntityNotFoundException;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.samples.s09.ticketing.domain.TicketOrder;
import com.example.samples.s09.ticketing.domain.TicketOrderId;
import com.example.samples.s09.ticketing.domain.TicketOrders;
import com.example.samples.s09.ticketing.domain.TicketingErrorCode;
import org.springframework.stereotype.Component;

/** The last forward step. Idempotent, and loud if the flow asks for it after a cancellation. */
@Component
class IssueTicketHandler implements CommandHandler<IssueTicket, Void> {

  private final TicketOrders orders;
  private final TicketingProcess process;

  IssueTicketHandler(TicketOrders orders, TicketingProcess process) {
    this.orders = orders;
    this.process = process;
  }

  @Override
  public Void handle(IssueTicket command, CommandContext context) {
    TicketOrderId id = new TicketOrderId(command.orderId());
    TicketOrder order =
        orders
            .find(id)
            .orElseThrow(
                () ->
                    new EntityNotFoundException(
                        TicketingErrorCode.ORDER_NOT_FOUND, "no order " + command.orderId()));

    order.issueTicket();
    orders.save(order);
    process.ticketIssued(command.orderId(), context);
    return null;
  }
}
