package com.acme.samples.s2.ordering.application.order;

import com.acme.samples.s2.ordering.domain.order.Order;
import com.acme.samples.s2.ordering.domain.order.Orders;
import com.acme.samples.s2.shared.DomainEvents;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies Inventory's decision to the order. Idempotent (inbox, G4), drives the
 * aggregate's own state machine (confirm/cancel, G2) rather than a blind status
 * write, and publishes the resulting domain event so the read model is projected.
 * Inbox check, state change, and event publication all share one transaction.
 */
@Service
public class ConfirmOrderService {

    private final Orders orders;
    private final ProcessedResults processed;
    private final DomainEvents domainEvents;

    public ConfirmOrderService(Orders orders, ProcessedResults processed, DomainEvents domainEvents) {
        this.orders = orders;
        this.processed = processed;
        this.domainEvents = domainEvents;
    }

    @Transactional
    public void apply(String orderId, boolean reserved) {
        if (processed.alreadyApplied(orderId)) return;   // inbox: converge on redelivery

        Order order = orders.byId(orderId)
                .orElseThrow(() -> new IllegalArgumentException("unknown order: " + orderId));

        if (reserved) {
            order.confirm();
        } else {
            order.cancel();
        }
        orders.updateStatus(order.id(), order.status());
        processed.markApplied(orderId);
        domainEvents.publish(order.domainEvents());
    }
}
