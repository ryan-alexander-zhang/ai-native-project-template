package com.acme.samples.s2.ordering.application.order;

import com.acme.samples.s2.ordering.api.OrderCancelled;
import com.acme.samples.s2.ordering.api.OrderPlaced;
import com.acme.samples.s2.ordering.domain.order.OrderCancelledEvent;
import com.acme.samples.s2.ordering.domain.order.OrderConfirmedEvent;
import com.acme.samples.s2.ordering.domain.order.OrderPlacedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * In-process domain-event handler (analysis-00005 §3). Two responsibilities:
 * <ol>
 *   <li><b>ACL / translation</b> — map the rich domain {@link OrderPlacedEvent}
 *       to the thin cross-context integration event
 *       {@link OrderPlaced} and hand it to the transactional outbox
 *       ({@link IntegrationEvents}). This keeps the internal model decoupled
 *       from the published contract (analysis-00002).</li>
 *   <li><b>Projection</b> — update the CQRS read model (analysis-00005 §5).</li>
 * </ol>
 *
 * <p>Runs on a plain {@link EventListener} — synchronous, same thread, same
 * transaction (analysis-00001 §2) — so the outbox row and the projection commit
 * atomically with the order. Deliberately NOT {@code @TransactionalEventListener(AFTER_COMMIT)},
 * which would break outbox atomicity (analysis-00005 §3).
 */
@Component
public class OrderEventsHandler {

    private final IntegrationEvents integrationEvents;
    private final OrderProjection projection;

    public OrderEventsHandler(IntegrationEvents integrationEvents, OrderProjection projection) {
        this.integrationEvents = integrationEvents;
        this.projection = projection;
    }

    @EventListener
    void on(OrderPlacedEvent event) {
        projection.placed(event.orderId(), event.total().amountMinor(), event.total().currency());
        integrationEvents.publish(new OrderPlaced(
                event.orderId(),
                event.customerId(),
                event.lines().stream()
                        .map(l -> new OrderPlaced.Line(l.sku(), l.qty(), l.unitPriceMinor()))
                        .toList()));
    }

    @EventListener
    void on(OrderConfirmedEvent event) {
        projection.statusChanged(event.orderId(), "CONFIRMED");
    }

    @EventListener
    void on(OrderCancelledEvent event) {
        projection.statusChanged(event.orderId(), "CANCELLED");
        // compensation: tell Inventory to release any stock it reserved for this order
        integrationEvents.publish(new OrderCancelled(event.orderId()));
    }
}
