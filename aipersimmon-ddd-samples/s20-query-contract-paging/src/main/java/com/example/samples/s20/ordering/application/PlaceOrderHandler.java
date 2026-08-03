package com.example.samples.s20.ordering.application;

import com.aipersimmon.ddd.core.id.IdGenerator;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.samples.s20.ordering.domain.Order;
import com.example.samples.s20.ordering.domain.OrderId;
import com.example.samples.s20.ordering.domain.Orders;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Component;

/**
 * Mints the id and the sort key.
 *
 * <p>{@code placedAt} is truncated to microseconds before it is written, because that is the
 * precision {@code timestamptz} keeps. Writing nanoseconds and reading microseconds back would mean
 * the value in memory and the value in the column disagree — harmless until a cursor is built from
 * one and compared against the other.
 */
@Component
class PlaceOrderHandler implements CommandHandler<PlaceOrder, String> {

  private final Orders orders;
  private final IdGenerator idGenerator;
  private final Clock clock;

  PlaceOrderHandler(Orders orders, IdGenerator idGenerator, Clock clock) {
    this.orders = orders;
    this.idGenerator = idGenerator;
    this.clock = clock;
  }

  @Override
  public String handle(PlaceOrder command, CommandContext context) {
    OrderId id = new OrderId(idGenerator.newId());
    Instant placedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
    orders.save(Order.place(id, command.customerId(), command.quantity(), placedAt));
    return id.value();
  }
}
