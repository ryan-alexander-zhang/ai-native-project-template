package com.example.samples.s12.ordering.application;

import com.aipersimmon.ddd.core.id.IdGenerator;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.samples.s12.ordering.domain.Order;
import com.example.samples.s12.ordering.domain.OrderId;
import com.example.samples.s12.ordering.domain.Orders;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Place the order, freezing each line's product name as it is right now.
 *
 * <p>The name comes from this context's own replica, not from a call to the catalogue. Two consequences,
 * both intended: placing an order does not depend on the catalogue being up, and the frozen name is
 * whatever this context believed at the time — which is the honest definition of "what the customer was
 * shown". A sku the replica has never heard of freezes as its sku, exactly as the projection displays it.
 *
 * <p>No publishing here: the aggregate records {@code OrderPlaced} and the repository drains it. The
 * projection is a subscriber and this handler does not know it exists.
 */
@Component
class PlaceOrderHandler implements CommandHandler<PlaceOrder, String> {

  private final Orders orders;
  private final ProductNames productNames;
  private final IdGenerator idGenerator;
  private final Clock clock;

  PlaceOrderHandler(
      Orders orders, ProductNames productNames, IdGenerator idGenerator, Clock clock) {
    this.orders = orders;
    this.productNames = productNames;
    this.idGenerator = idGenerator;
    this.clock = clock;
  }

  @Override
  public String handle(PlaceOrder command, CommandContext context) {
    OrderId id = new OrderId(idGenerator.newId());
    List<Order.Line> lines =
        command.lines().stream()
            .map(
                line ->
                    new Order.Line(
                        line.sku(),
                        line.quantity(),
                        line.unitPriceMinor(),
                        productNames.nameOf(line.sku()).orElse(line.sku())))
            .toList();

    orders.save(Order.place(id, command.customerId(), lines, clock.instant()));
    return id.value();
  }
}
