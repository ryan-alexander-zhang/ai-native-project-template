package com.example.howto;

import com.aipersimmon.ddd.cqrs.AggregateCollector;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Thin write-side handler: it persists the order and registers the aggregate with
 * the collector so the transaction interceptor drains its domain event afterwards.
 * It opens no transaction and publishes nothing itself — the bus chain does that.
 *
 * <p>A concrete class (not a lambda) so the bus can index it by its command type.
 * Passing a sku of {@code "BOOM"} throws after the insert to show that the whole
 * unit of work — the row, the event, and the read-model update — rolls back.
 */
@Component
public class PlaceOrderHandler implements CommandHandler<PlaceOrder, String> {

    private final JdbcTemplate jdbc;
    private final AggregateCollector collector;

    public PlaceOrderHandler(JdbcTemplate jdbc, AggregateCollector collector) {
        this.jdbc = jdbc;
        this.collector = collector;
    }

    @Override
    public String handle(PlaceOrder command) {
        jdbc.update("INSERT INTO orders (id, sku, status) VALUES (?, ?, 'PLACED')",
                command.orderId(), command.sku());
        collector.register(new Order(command.orderId(), command.sku()));
        if ("BOOM".equals(command.sku())) {
            throw new IllegalStateException("simulated failure after the write");
        }
        return command.orderId();
    }
}
