package com.example.ordering.application.fulfilment;

import com.aipersimmon.ddd.application.DomainEvents;
import com.aipersimmon.ddd.application.IntegrationEvents;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.example.ordering.api.OrderReadyForFulfilment;
import com.example.ordering.domain.order.LineData;
import com.example.ordering.domain.order.Order;
import com.example.ordering.domain.order.Orders;
import org.springframework.stereotype.Component;

/**
 * The single application-layer step that moves a <em>ready</em> order into fulfilment. Both entry
 * points that make an order ready converge here: {@code PlaceOrderHandler} for an order needing no
 * review, and {@code ApproveReviewHandler} once review is approved. Centralising it keeps the
 * "ready ⇒ begin fulfilment ⇒ ask inventory to reserve" rule in one place and lets the caller's
 * {@link CommandContext} propagate to the reservation, so the causal chain stays intact.
 *
 * <p>It does three things, in the caller's transaction: transitions the aggregate to {@code
 * FULFILMENT_IN_PROGRESS} (moving past the customer's self-cancel window), persists it and
 * publishes its domain events — including {@code OrderReadyForFulfilmentEvent}, which the {@link
 * OrderFulfilmentStarter} bridge turns into the durable process start — and announces the {@link
 * OrderReadyForFulfilment} integration event that asks the inventory context to reserve stock.
 */
@Component
public class FulfilmentTrigger {

  private final Orders orders;
  private final DomainEvents domainEvents;
  private final IntegrationEvents integrationEvents;

  public FulfilmentTrigger(
      Orders orders, DomainEvents domainEvents, IntegrationEvents integrationEvents) {
    this.orders = orders;
    this.domainEvents = domainEvents;
    this.integrationEvents = integrationEvents;
  }

  /**
   * Begin fulfilment for an order that has just become ready. The order must be in {@code
   * READY_FOR_FULFILMENT}; the aggregate guards that transition.
   */
  public void begin(Order order, CommandContext context) {
    order.beginFulfilment();
    orders.save(order);
    domainEvents.publishAndClear(order);
    integrationEvents.publish(reservationRequest(order), context);
  }

  private static OrderReadyForFulfilment reservationRequest(Order order) {
    var lines = order.lineData().stream().map(FulfilmentTrigger::toLine).toList();
    return new OrderReadyForFulfilment(order.id().value(), lines);
  }

  private static OrderReadyForFulfilment.Line toLine(LineData line) {
    return new OrderReadyForFulfilment.Line(line.sku(), line.quantity());
  }
}
