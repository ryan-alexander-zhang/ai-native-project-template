package com.example.inventory.adapter.messaging;

import com.aipersimmon.ddd.application.InboundEvents;
import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.integration.EventEnvelope;
import com.example.inventory.application.stock.ReserveStock;
import com.example.ordering.api.OrderReadyForFulfilment;
import java.util.List;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Reacts to ordering's "an order is ready" announcement by sending a {@link ReserveStock} command
 * through the command bus. Ordering announces it only once an order is cleared for fulfilment (past
 * manual review, if any), so inventory reserves nothing for an order still awaiting review. As the
 * anti-corruption layer it receives the full {@link EventEnvelope}, reads only the published
 * contract of the ordering context (keeping the two contexts decoupled), and maps the envelope's
 * metadata to a {@link CommandContext} so the reservation — and the events it emits — stay
 * correlated to the order that caused them.
 *
 * <h2>Two revisions on the topic, one method here</h2>
 *
 * <p>This is where the scaffold shows what a published-event version bump costs a consumer: one
 * upcaster registration, not one listener method per historical revision. Two revisions of the
 * contract are on the topic — {@link OrderReadyForFulfilment} (v2) and the retired {@code
 * OrderReadyForFulfilmentV1} — and this class used to carry a method for each, every future
 * revision widening it further. The {@link OrderReadyForFulfilmentV1Upcaster} moved that cost to
 * the contract boundary: the consumer bridge upcasts a v1 record before dispatch, so a single
 * {@code EventEnvelope<OrderReadyForFulfilment>} method receives both revisions — and the
 * envelope's version says v2, the revision actually delivered.
 *
 * <p><strong>The application layer is untouched, still.</strong> {@code ReserveStock} did not
 * change, {@code ReserveStockHandler} did not change, and neither knows a version exists. That was
 * the property worth having when the version test lived in two listener methods, and it survives
 * the move outward: the boundary absorbs the producer's schema history — now one layer earlier.
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
    List<ReserveStock.Line> lines =
        event.lines().stream()
            .map(line -> new ReserveStock.Line(line.sku(), line.quantity()))
            .toList();
    commandBus.send(
        new ReserveStock(event.orderId(), lines), InboundEvents.commandContext(envelope));
  }
}
