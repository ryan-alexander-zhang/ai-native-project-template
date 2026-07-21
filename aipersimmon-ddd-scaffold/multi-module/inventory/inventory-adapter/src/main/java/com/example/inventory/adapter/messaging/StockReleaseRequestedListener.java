package com.example.inventory.adapter.messaging;

import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.integration.EventEnvelope;
import com.example.inventory.application.stock.ReleaseStock;
import com.example.ordering.api.StockReleaseRequested;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Reacts to ordering's {@link StockReleaseRequested} integration event by sending a {@link
 * ReleaseStock} command through the command bus. The mirror of {@link OrderPlacedListener} on the
 * compensation path: it reads only ordering's published contract and keeps the causing event's
 * context so the {@code StockReleased} it triggers stays correlated to the order.
 */
@Component
public class StockReleaseRequestedListener {

  private final CommandBus commandBus;

  public StockReleaseRequestedListener(CommandBus commandBus) {
    this.commandBus = commandBus;
  }

  @EventListener
  public void on(EventEnvelope<StockReleaseRequested> envelope) {
    StockReleaseRequested event = envelope.payload();
    commandBus.send(new ReleaseStock(event.reservationId()), CommandContext.of(envelope));
  }
}
