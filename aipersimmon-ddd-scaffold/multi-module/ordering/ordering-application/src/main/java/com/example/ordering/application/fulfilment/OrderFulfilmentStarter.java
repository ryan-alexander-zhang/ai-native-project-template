package com.example.ordering.application.fulfilment;

import com.aipersimmon.ddd.application.DomainEventHandler;
import com.example.ordering.domain.order.OrderPlacedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Application-layer subscriber that starts the order-fulfilment flow when an order
 * is placed. It reacts to the context's internal {@link OrderPlacedEvent} — a domain
 * event, consumed within the context — and hands the order id to the
 * {@link OrderFulfilmentProcessManager}.
 *
 * <p>Domain-event subscribers belong here, in the application layer, not in an
 * inbound adapter: an adapter's job is to translate an external transport (HTTP, a
 * cross-context integration event) into a command, and it should not reach into the
 * context's own domain types. Keeping this subscription in the application layer is
 * why {@code ordering-adapter} needs no dependency on {@code ordering-domain}.
 *
 * <p>The domain event is published in-process, synchronously, within the
 * place-order transaction, so the saga always exists before inventory's response
 * arrives — the same timing guarantee, now without an adapter touching the domain.
 */
@Component
@DomainEventHandler
public class OrderFulfilmentStarter {

    private final OrderFulfilmentProcessManager process;

    public OrderFulfilmentStarter(OrderFulfilmentProcessManager process) {
        this.process = process;
    }

    @EventListener
    public void onOrderPlaced(OrderPlacedEvent event) {
        process.onOrderPlaced(event.orderId().value());
    }
}
