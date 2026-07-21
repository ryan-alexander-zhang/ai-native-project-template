package com.example.ordering.application.fulfilment;

import com.aipersimmon.ddd.application.DomainEventHandler;
import com.example.ordering.domain.order.OrderCancelledEvent;
import com.example.ordering.domain.order.OrderConfirmedEvent;
import com.example.ordering.domain.order.OrderPlacedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Application-layer subscriber that bridges the ordering context's own domain events to the {@link
 * OrderFulfilmentProcess}. It <em>starts</em> the flow on {@link OrderPlacedEvent}, and it feeds
 * the flow's terminal facts back in: {@link OrderConfirmedEvent} and {@link OrderCancelledEvent}
 * are what let the saga reach a terminal status on the confirmed outcome, rather than the moment a
 * confirm/cancel command was merely sent.
 *
 * <p>Domain-event subscribers belong here, in the application layer, not in an inbound adapter: an
 * adapter translates an external transport into a command and must not reach into the context's own
 * domain types. Keeping this subscription in the application layer is why {@code ordering-adapter}
 * needs no dependency on {@code ordering-domain}. The events are published in-process,
 * synchronously, within the transaction that recorded them.
 */
@Component
@DomainEventHandler
public class OrderFulfilmentStarter {

  private final OrderFulfilmentProcess process;

  public OrderFulfilmentStarter(OrderFulfilmentProcess process) {
    this.process = process;
  }

  @EventListener
  public void onOrderPlaced(OrderPlacedEvent event) {
    process.placed(event.orderId().value());
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
