package com.example.ordering.application.fulfilment;

import com.aipersimmon.ddd.application.DomainEventHandler;
import com.example.ordering.domain.order.OrderCancelledEvent;
import com.example.ordering.domain.order.OrderConfirmedEvent;
import com.example.ordering.domain.order.OrderReadyForFulfilmentEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Application-layer subscriber that bridges the ordering context's own domain events to the {@link
 * OrderFulfilmentProcess}. It <em>starts</em> the flow on {@link OrderReadyForFulfilmentEvent} —
 * the fact that the order has cleared for fulfilment (past manual review, if any), not merely that
 * it was created — and it feeds the flow's terminal facts back in: {@link OrderConfirmedEvent} and
 * {@link OrderCancelledEvent} are what let the process manager reach a terminal status on the
 * confirmed outcome, rather than the moment a confirm/cancel command was merely sent.
 *
 * <p>Domain-event subscribers belong here, in the application layer, not in an inbound adapter: an
 * adapter translates an external transport into a command and must not reach into the context's own
 * domain types. Keeping this subscription in the application layer is why {@code ordering-adapter}
 * needs no dependency on {@code ordering-domain}. The events are published in-process,
 * synchronously, within the transaction that recorded them.
 *
 * <p><strong>That synchronous, in-transaction delivery is load-bearing, not incidental.</strong>
 * The process instance is created in the same commit that recorded the order's readiness, so either
 * both exist afterwards or neither does — the fact and the flow it starts cannot come apart. The
 * framework's drain-on-save is what guarantees these listeners run inside that transaction.
 * Swapping this to {@code @Async} or {@code @TransactionalEventListener(AFTER_COMMIT)} would
 * silently break it: a crash between commit and listener would leave a ready order no process ever
 * picks up, with no error anywhere. If the start must move out of the transaction, the correct
 * shape is an integration event through the outbox — which is durable precisely so it may be
 * asynchronous.
 */
@Component
@DomainEventHandler
public class OrderFulfilmentStarter {

  private final OrderFulfilmentProcess process;

  public OrderFulfilmentStarter(OrderFulfilmentProcess process) {
    this.process = process;
  }

  @EventListener
  public void onOrderReadyForFulfilment(OrderReadyForFulfilmentEvent event) {
    process.readyForFulfilment(event.orderId().value());
  }

  @EventListener
  public void onOrderConfirmed(OrderConfirmedEvent event) {
    process.orderConfirmed(event.orderId().value());
  }

  @EventListener
  public void onOrderCancelled(OrderCancelledEvent event) {
    process.orderCancelled(event.orderId().value());
  }
}
