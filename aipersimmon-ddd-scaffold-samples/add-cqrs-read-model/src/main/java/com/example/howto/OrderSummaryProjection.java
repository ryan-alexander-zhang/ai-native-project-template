package com.example.howto;

import com.aipersimmon.ddd.cqrs.Projection;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Maintains the {@link OrderSummary} read model from domain events. It listens for
 * {@link OrderPlaced}, which the transaction interceptor publishes within the
 * command's transaction, so the read-model row and the write-model row commit
 * together. Because delivery is synchronous and same-transaction, a rollback of
 * the command undoes this update too.
 */
@Component
@Projection
public class OrderSummaryProjection {

    private final JdbcTemplate jdbc;

    public OrderSummaryProjection(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener
    public void on(OrderPlaced event) {
        jdbc.update("INSERT INTO order_summary (order_id, sku, status) VALUES (?, ?, 'PLACED')",
                event.orderId(), event.sku());
    }
}
