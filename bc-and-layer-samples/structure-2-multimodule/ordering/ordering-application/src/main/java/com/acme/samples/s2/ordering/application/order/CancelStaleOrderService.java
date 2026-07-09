package com.acme.samples.s2.ordering.application.order;

import com.acme.samples.s2.ordering.domain.order.Order;
import com.acme.samples.s2.ordering.domain.order.OrderStatus;
import com.acme.samples.s2.ordering.domain.order.Orders;
import com.acme.samples.s2.shared.AggregateChanges;
import com.acme.samples.s2.shared.CommandHandler;
import org.springframework.stereotype.Service;

/**
 * Command handler for {@link CancelStaleOrderCommand}. Cancels a still-PENDING
 * order on timeout (saga compensation, G6). Idempotent via the status guard: if a
 * late stock result already resolved the order (CONFIRMED/CANCELLED), it is a
 * no-op. The resulting OrderCancelledEvent is translated to the OrderCancelled
 * integration event (OrderEventsHandler) so Inventory releases any reserved stock.
 */
@Service
public class CancelStaleOrderService implements CommandHandler<CancelStaleOrderCommand, Void> {

    private final Orders orders;
    private final AggregateChanges changes;

    public CancelStaleOrderService(Orders orders, AggregateChanges changes) {
        this.orders = orders;
        this.changes = changes;
    }

    @Override
    public Void handle(CancelStaleOrderCommand command) {
        Order order = orders.byId(command.orderId()).orElse(null);
        if (order == null || order.status() != OrderStatus.PENDING) {
            return null;   // already resolved by a (late) stock result, or gone
        }
        order.cancel();
        orders.updateStatus(order.id(), order.status());
        changes.register(order);
        return null;
    }
}
