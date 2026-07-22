package com.example.inventory.adapter.messaging;

import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.integration.EventEnvelope;
import com.example.inventory.application.stock.ReserveStock;
import com.example.ordering.api.OrderReadyForFulfilment;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Reacts to ordering's {@link OrderReadyForFulfilment} integration event by sending a {@link
 * ReserveStock} command through the command bus. Ordering announces this only once an order is
 * cleared for fulfilment (past manual review, if any), so inventory reserves nothing for an order
 * still awaiting review. As the anti-corruption layer it receives the full {@link EventEnvelope},
 * reads only the published contract of the ordering context (keeping the two contexts decoupled),
 * and maps the envelope's metadata to a {@link CommandContext} so the reservation — and the events
 * it emits — stay correlated to the order that caused them.
 */
@Component
public class OrderReadyForFulfilmentListener {

  private final CommandBus commandBus;

  public OrderReadyForFulfilmentListener(CommandBus commandBus) {
    this.commandBus = commandBus;
  }

  @EventListener
  public void on(EventEnvelope<OrderReadyForFulfilment> envelope) {
    OrderReadyForFulfilment event = envelope.payload();
    var lines =
        event.lines().stream()
            .map(line -> new ReserveStock.Line(line.sku(), line.quantity()))
            .toList();
    commandBus.send(new ReserveStock(event.orderId(), lines), CommandContext.of(envelope));
  }
}
