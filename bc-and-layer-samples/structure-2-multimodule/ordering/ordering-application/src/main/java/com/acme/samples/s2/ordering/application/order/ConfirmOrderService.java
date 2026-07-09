package com.acme.samples.s2.ordering.application.order;

import com.acme.samples.s2.ordering.domain.order.Order;
import com.acme.samples.s2.ordering.domain.order.Orders;
import com.acme.samples.s2.shared.AggregateChanges;
import com.acme.samples.s2.shared.CommandHandler;
import org.springframework.stereotype.Service;

/**
 * Command handler for {@link ConfirmOrderCommand}. Idempotent (inbox, G4), drives
 * the aggregate's state machine (confirm/cancel, G2), and registers the aggregate
 * for domain-event drain. No {@code @Transactional} — the CommandBus's Transaction
 * decorator owns the UnitOfWork (analysis-00005 §5.1).
 */
@Service
public class ConfirmOrderService implements CommandHandler<ConfirmOrderCommand, Void> {

    private final Orders orders;
    private final ProcessedResults processed;
    private final AggregateChanges changes;

    public ConfirmOrderService(Orders orders, ProcessedResults processed, AggregateChanges changes) {
        this.orders = orders;
        this.processed = processed;
        this.changes = changes;
    }

    @Override
    public Void handle(ConfirmOrderCommand command) {
        String orderId = command.orderId();
        if (processed.alreadyApplied(orderId)) return null;   // inbox: converge on redelivery

        Order order = orders.byId(orderId)
                .orElseThrow(() -> new IllegalArgumentException("unknown order: " + orderId));

        if (command.reserved()) {
            order.confirm();
        } else {
            order.cancel();
        }
        orders.updateStatus(order.id(), order.status());
        processed.markApplied(orderId);
        changes.register(order);
        return null;
    }
}
