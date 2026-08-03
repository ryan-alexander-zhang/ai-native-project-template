package com.example.ordering.application.fulfilment;

import com.aipersimmon.ddd.application.IntegrationEvents;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.example.ordering.api.OrderReadyForFulfilment;
import com.example.ordering.domain.order.LineData;
import com.example.ordering.domain.order.Order;
import com.example.ordering.domain.order.Orders;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The single application-layer step that moves a <em>ready</em> order into fulfilment. Both entry
 * points that make an order ready converge here: {@code PlaceOrderHandler} for an order needing no
 * review, and {@code ApproveReviewHandler} once review is approved. Centralising it keeps the
 * "ready ⇒ begin fulfilment ⇒ ask inventory to reserve" rule in one place and lets the caller's
 * {@link CommandContext} propagate to the reservation, so the causal chain stays intact.
 *
 * <p>It does two things, in the caller's transaction: persists the <em>ready</em> order and
 * publishes its domain events — including {@code OrderReadyForFulfilmentEvent}, which the {@link
 * OrderFulfilmentStarter} bridge turns into the durable process start — and announces the {@link
 * OrderReadyForFulfilment} integration event that asks the inventory context to reserve stock.
 *
 * <p><strong>What it deliberately no longer does is advance the aggregate.</strong> It used to call
 * {@code beginFulfilment()} here, which meant a brand-new order was INSERTed as {@code
 * FULFILMENT_IN_PROGRESS} and no row ever held {@code READY_FOR_FULFILMENT} — the state existed for
 * a few microseconds inside one transaction. Since that is the state the customer's self-cancel
 * window is defined over ({@code CancellableByCustomer.BEFORE_FULFILMENT}), the window was
 * unreachable for any order that did not happen to be held for manual review.
 *
 * <p>The two facts had been collapsed into one method, and they are not the same fact: "this order
 * is cleared" is this context's own conclusion, while "fulfilment has begun" is a claim about work
 * elsewhere actually having started. Announcing a reservation request is not having a reservation.
 * The transition now happens when inventory answers, driven by the process manager through {@code
 * BeginFulfilment} — which also makes {@code FULFILMENT_IN_PROGRESS} mean what its own javadoc says
 * it means.
 *
 * <p>Note what has <em>not</em> changed: the first reservation request is still initiated by the
 * application from the ready moment, not by a process effect. That was a deliberate design choice
 * and it still holds; only the moment the aggregate advances has moved.
 */
@Component
public class FulfilmentTrigger {

  private final Orders orders;
  private final IntegrationEvents integrationEvents;
  private final Clock clock;

  /**
   * How long the fulfilment process will wait for a reservation before compensating. Read from the
   * same property {@code OrderFulfilmentDefinition} arms its STOCK deadline from, so the deadline
   * this context publishes and the deadline it actually enforces cannot disagree — two properties
   * would drift, and the published one would become a promise nothing kept.
   */
  private final Duration stockTimeout;

  public FulfilmentTrigger(
      Orders orders,
      IntegrationEvents integrationEvents,
      Clock clock,
      @Value("${ordering.fulfilment.stock-timeout:PT1M}") Duration stockTimeout) {
    this.orders = orders;
    this.integrationEvents = integrationEvents;
    this.clock = clock;
    this.stockTimeout = stockTimeout;
  }

  /**
   * Record an order that has just become ready and ask inventory to reserve for it. The order stays
   * in {@code READY_FOR_FULFILMENT} — it advances only when the reservation exists.
   */
  public void begin(Order order, CommandContext context) {
    orders.save(order);
    integrationEvents.publish(reservationRequest(order), context);
  }

  private OrderReadyForFulfilment reservationRequest(Order order) {
    var lines = order.lineData().stream().map(FulfilmentTrigger::toLine).toList();
    // Revision 2 of the contract, and the only revision anything publishes. The deadline is stated
    // rather than implied: inventory would otherwise have no way to know how long this context is
    // prepared to wait, and no way to derive it — the budget is ordering's own configuration.
    return new OrderReadyForFulfilment(
        order.id().value(), lines, clock.instant().plus(stockTimeout));
  }

  private static OrderReadyForFulfilment.Line toLine(LineData line) {
    // Unwrapped on the way out: the published contract stays flat, so a consumer never has to
    // depend on ordering's Sku to read ordering's events.
    return new OrderReadyForFulfilment.Line(line.sku().value(), line.quantity());
  }
}
