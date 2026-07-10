package com.example.howto;

import com.aipersimmon.ddd.application.IntegrationEvents;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Handles {@link ReserveStock}. It decrements stock and publishes the outcome as an
 * integration event through {@link IntegrationEvents} (the outbox writer). Because
 * the command bus runs the handler in one transaction, the stock change and the
 * outbox row commit or roll back together — reliable outbound, no dual-write.
 *
 * <p>A sku starting with {@code BOOM} throws after publishing, to demonstrate that
 * the outbox row rolls back with the failed write (nothing is emitted).
 */
@Component
public class ReserveStockHandler implements CommandHandler<ReserveStock, Void> {

    private final JdbcTemplate jdbc;
    private final IntegrationEvents integrationEvents;

    public ReserveStockHandler(JdbcTemplate jdbc, IntegrationEvents integrationEvents) {
        this.jdbc = jdbc;
        this.integrationEvents = integrationEvents;
    }

    @Override
    public Void handle(ReserveStock command) {
        int reserved = jdbc.update(
                "UPDATE stock SET available = available - ? WHERE sku = ? AND available >= ?",
                command.quantity(), command.sku(), command.quantity());
        if (reserved == 1) {
            integrationEvents.publish(new StockReserved(command.orderId()));
        } else {
            integrationEvents.publish(new StockReservationFailed(
                    command.orderId(), "cannot reserve " + command.quantity() + " of " + command.sku()));
        }
        if (command.sku().startsWith("BOOM")) {
            throw new IllegalStateException("simulated failure after publishing");
        }
        return null;
    }
}
