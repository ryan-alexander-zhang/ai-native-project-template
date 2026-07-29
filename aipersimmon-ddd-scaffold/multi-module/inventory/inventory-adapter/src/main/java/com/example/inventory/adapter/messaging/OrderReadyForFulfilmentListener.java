package com.example.inventory.adapter.messaging;

import com.aipersimmon.ddd.application.InboundEvents;
import com.aipersimmon.ddd.cqrs.CommandBus;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.integration.EventEnvelope;
import com.example.inventory.application.stock.ReserveStock;
import com.example.ordering.api.OrderReadyForFulfilment;
import com.example.ordering.api.OrderReadyForFulfilmentV1;
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
 * <h2>Two revisions in, one command out</h2>
 *
 * <p>This is where the scaffold shows what a published-event version bump actually costs a
 * consumer. There are two listeners because two revisions of the contract are on the topic — {@link
 * OrderReadyForFulfilment} (v2) and {@link OrderReadyForFulfilmentV1} — and both funnel into one
 * {@link #reserve} call.
 *
 * <p><strong>The application layer is untouched.</strong> {@code ReserveStock} did not change,
 * {@code ReserveStockHandler} did not change, and neither knows a version exists. That is the
 * property worth having, and it is what an anticorruption layer is <em>for</em>: the boundary
 * absorbs the producer's schema history instead of letting it leak inward as a conditional in a use
 * case. Had the version test landed in the handler, every future revision would have widened it.
 *
 * <p><strong>Why two listeners rather than one over a shared supertype.</strong> The two revisions
 * deliberately share no Java type. Dispatch is by envelope payload type, and a listener per
 * revision is also what lets the library narrow which {@code (name, version)} pairs this
 * application handles at all; a common interface would defeat that narrowing and would tie the
 * frozen revision to the live one.
 *
 * <p><strong>The upcast, and why it supplies nothing.</strong> v2 added {@code
 * reservationDeadline}. A v1 message does not carry one, and none can be recovered: the value is
 * ordering's configured timeout added to the moment it published, and the envelope's {@code
 * occurredAt} gives the moment but not the budget. So the v1 path states no deadline — which is
 * precisely what v1 always meant. Inventory does not act on the field today, so both paths
 * currently produce an identical command; writing the upcast as a deliberate "unknown" rather than
 * a plausible-looking instant is what keeps that honest on the day a consumer does act on it,
 * because a fabricated deadline is indistinguishable from a real one at the point of use.
 */
@Component
public class OrderReadyForFulfilmentListener {

  private final CommandBus commandBus;

  public OrderReadyForFulfilmentListener(CommandBus commandBus) {
    this.commandBus = commandBus;
  }

  /** The current revision. */
  @EventListener
  public void on(EventEnvelope<OrderReadyForFulfilment> envelope) {
    OrderReadyForFulfilment event = envelope.payload();
    List<ReserveStock.Line> lines =
        event.lines().stream()
            .map(line -> new ReserveStock.Line(line.sku(), line.quantity()))
            .toList();
    reserve(event.orderId(), lines, envelope);
  }

  /**
   * The retired revision, still arriving until the topic's retention and the inbox's redelivery
   * window have drained behind the deployment that stopped publishing it. Deleting this method
   * early is what would turn that remaining backlog into dead letters.
   */
  @EventListener
  public void onV1(EventEnvelope<OrderReadyForFulfilmentV1> envelope) {
    OrderReadyForFulfilmentV1 event = envelope.payload();
    List<ReserveStock.Line> lines =
        event.lines().stream()
            .map(line -> new ReserveStock.Line(line.sku(), line.quantity()))
            .toList();
    reserve(event.orderId(), lines, envelope);
  }

  /** The one path into the application layer, reached identically from either revision. */
  private void reserve(String orderId, List<ReserveStock.Line> lines, EventEnvelope<?> envelope) {
    commandBus.send(new ReserveStock(orderId, lines), InboundEvents.commandContext(envelope));
  }
}
