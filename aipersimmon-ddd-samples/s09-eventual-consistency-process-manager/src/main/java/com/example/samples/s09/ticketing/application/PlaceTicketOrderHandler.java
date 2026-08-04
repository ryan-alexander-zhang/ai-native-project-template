package com.example.samples.s09.ticketing.application;

import com.aipersimmon.ddd.core.id.IdGenerator;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.samples.s09.ticketing.domain.TicketOrder;
import com.example.samples.s09.ticketing.domain.TicketOrderId;
import com.example.samples.s09.ticketing.domain.TicketOrders;
import org.springframework.stereotype.Component;

/**
 * Create the order and start its flow, in one transaction.
 *
 * <p>Both writes are local — the order row and the process instance row — so there is no state in which
 * an order exists with nobody coordinating it, or a flow exists for an order that does not. That is the
 * same argument as S4's outbox row, applied to a coordinator: the durable start is a row, not a thread.
 *
 * <p>Nothing is dispatched from here. The flow's first command is an <em>effect</em>, staged in this
 * transaction and delivered by the relay after it commits, which is what keeps a network-ish call out of
 * a transaction and makes the whole chain replayable.
 */
@Component
class PlaceTicketOrderHandler implements CommandHandler<PlaceTicketOrder, String> {

  private final TicketOrders orders;
  private final TicketingProcess process;
  private final IdGenerator idGenerator;

  PlaceTicketOrderHandler(
      TicketOrders orders, TicketingProcess process, IdGenerator idGenerator) {
    this.orders = orders;
    this.process = process;
    this.idGenerator = idGenerator;
  }

  @Override
  public String handle(PlaceTicketOrder command, CommandContext context) {
    TicketOrderId id = new TicketOrderId(idGenerator.newId());
    orders.save(
        TicketOrder.place(id, command.customerId(), command.seatClass(), command.amountMinor()));
    process.orderPlaced(
        id.value(), command.customerId(), command.seatClass(), command.amountMinor(), context);
    return id.value();
  }
}
