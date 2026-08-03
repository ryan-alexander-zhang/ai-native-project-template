package com.example.samples.s04.ordering.application;

import com.aipersimmon.ddd.application.IntegrationEvents;
import com.aipersimmon.ddd.core.id.IdGenerator;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.samples.s04.ordering.api.OrderDrafted;
import com.example.samples.s04.ordering.api.OrderPlaced;
import com.example.samples.s04.ordering.domain.Order;
import com.example.samples.s04.ordering.domain.OrderId;
import com.example.samples.s04.ordering.domain.OrderLine;
import com.example.samples.s04.ordering.domain.Orders;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Save the aggregate, publish the fact. Four things about these two lines are the point of S4.
 *
 * <p><strong>The publication is a database write.</strong> {@code IntegrationEvents.publish} does not
 * talk to Kafka here — with an outbox on the classpath it inserts a row, in <em>this</em> transaction,
 * next to the order. So the two either both happen or neither does, which is the only way to avoid the
 * two failure modes nobody can fix afterwards: an order nobody was told about, and an announcement of
 * an order that does not exist. A test asserts both rows appear together, and that a refused command
 * leaves neither.
 *
 * <p><strong>The handler cannot tell which transport it has.</strong> Same call, same code, whether
 * the event is delivered in-process, relayed to Kafka, or (S5's direction) sent somewhere else
 * entirely. That is why "add a broker later" is a dependency and a topic name rather than a rewrite.
 *
 * <p><strong>The context is what makes the event traceable.</strong> Passing it is not ceremony: the
 * event inherits the command's {@code correlationId} and records the command's {@code messageId} as
 * its {@code causationId}, so the reservation the inventory service makes three hops later still
 * carries the id of the request that caused it. Nothing about this travels in the payload.
 *
 * <p><strong>Nothing is published by the aggregate.</strong> The aggregate registers no domain event
 * here; the handler decides what the outside world is told, because the published contract is an
 * application-level decision and the aggregate must not know it exists. (Domain events inside one
 * service are S3.)
 */
@Component
class PlaceOrderHandler implements CommandHandler<PlaceOrder, String> {

  private final Orders orders;
  private final IntegrationEvents integrationEvents;
  private final IdGenerator idGenerator;

  PlaceOrderHandler(Orders orders, IntegrationEvents integrationEvents, IdGenerator idGenerator) {
    this.orders = orders;
    this.integrationEvents = integrationEvents;
    this.idGenerator = idGenerator;
  }

  @Override
  public String handle(PlaceOrder command, CommandContext context) {
    OrderId id = new OrderId(idGenerator.newId());
    List<OrderLine> lines =
        command.lines().stream().map(line -> new OrderLine(line.sku(), line.quantity())).toList();

    orders.save(Order.place(id, command.customerId(), lines));

    if (command.draftOnly()) {
      // Same port, same transaction, same row in the outbox — and this one never leaves the service,
      // because its class carries no @Externalized.
      integrationEvents.publish(new OrderDrafted(id.value(), command.customerId()), context);
    } else {
      integrationEvents.publish(
          new OrderPlaced(
              id.value(),
              command.customerId(),
              lines.stream()
                  .map(line -> new OrderPlaced.Line(line.sku(), line.quantity()))
                  .toList()),
          context);
    }
    return id.value();
  }
}
