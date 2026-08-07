package com.example.samples.s09.ticketing.application;

import com.aipersimmon.ddd.cqrs.QueryHandler;
import com.example.samples.s09.ticketing.domain.TicketOrderId;
import com.example.samples.s09.ticketing.domain.TicketOrders;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The read a client polls while the process manager advances the order. It sits behind the bus, so
 * a poll is a dispatch like any other — the endpoint used to hold {@code TicketOrders} and read
 * outside every boundary the bus establishes, which is exactly the wrong place to read state that
 * another transaction is concurrently advancing.
 */
@Component
class FindTicketOrderHandler implements QueryHandler<FindTicketOrder, Optional<TicketOrderView>> {

  private final TicketOrders orders;

  FindTicketOrderHandler(TicketOrders orders) {
    this.orders = orders;
  }

  @Override
  public Optional<TicketOrderView> handle(FindTicketOrder query) {
    return orders
        .find(new TicketOrderId(query.orderId()))
        .map(
            order ->
                new TicketOrderView(
                    order.id().value(),
                    order.customerId(),
                    order.seatClass(),
                    order.amountMinor(),
                    order.status().name(),
                    order.cancelReason()));
  }
}
