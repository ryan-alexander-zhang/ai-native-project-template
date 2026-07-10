package com.example.howto;

import com.aipersimmon.ddd.application.IntegrationEvents;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Handles {@link ConfirmOrder}: sets the order to CONFIRMED and publishes
 * {@link OrderConfirmed} through the outbox, atomically in the command's transaction.
 */
@Component
public class ConfirmOrderHandler implements CommandHandler<ConfirmOrder, Void> {

    private final JdbcTemplate jdbc;
    private final IntegrationEvents integrationEvents;

    public ConfirmOrderHandler(JdbcTemplate jdbc, IntegrationEvents integrationEvents) {
        this.jdbc = jdbc;
        this.integrationEvents = integrationEvents;
    }

    @Override
    public Void handle(ConfirmOrder command) {
        jdbc.update("UPDATE orders SET status = 'CONFIRMED' WHERE id = ?", command.orderId());
        integrationEvents.publish(new OrderConfirmed(command.orderId()));
        return null;
    }
}
