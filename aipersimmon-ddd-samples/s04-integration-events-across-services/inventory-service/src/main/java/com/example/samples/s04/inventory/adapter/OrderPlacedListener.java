package com.example.samples.s04.inventory.adapter;

import com.aipersimmon.ddd.application.InboundEvents;
import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.integration.EventEnvelope;
import com.example.samples.s04.inventory.api.OrderPlaced;
import com.example.samples.s04.inventory.application.ReserveStock;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The inbound adapter: an event arrives, a command goes in. It holds no rule and makes no decision.
 *
 * <p>It lives in {@code ..adapter..} because the library's ArchUnit rule puts integration-event
 * subscribers there, and the rule is enforcing the reason: "an integration event arrives over a
 * transport at the boundary, so its subscriber is an inbound adapter that translates it and hands off
 * inward".
 *
 * <p><strong>It listens to an envelope, not a payload.</strong> The payload is the business data; the
 * envelope carries the causal ids, which is how a reservation stays traceable to the HTTP request that
 * placed the order two services ago. {@code InboundEvents.commandContext(envelope)} is the one place
 * that conversion is written. It does <em>not</em> pass the message id inward for deduplication: the
 * bridge has already done that before this method was called.
 *
 * <p><strong>Nothing here is Kafka.</strong> The same method receives the event when the transport is
 * a broker and when it is in-process delivery inside one deployable — the consumer bridge republishes
 * a consumed record through Spring's publisher, so a local run without Kafka exercises this code
 * unchanged. That is the "degrade to in-process" answer, and it costs no branch.
 */
@Component
class OrderPlacedListener {

  private final CommandBus commandBus;

  OrderPlacedListener(CommandBus commandBus) {
    this.commandBus = commandBus;
  }

  @EventListener
  void on(EventEnvelope<OrderPlaced> envelope) {
    OrderPlaced event = envelope.payload();
    commandBus.send(
        new ReserveStock(
            event.orderId(),
            event.lines().stream()
                .map(line -> new ReserveStock.Line(line.sku(), line.quantity()))
                .toList()),
        InboundEvents.commandContext(envelope));
  }
}
