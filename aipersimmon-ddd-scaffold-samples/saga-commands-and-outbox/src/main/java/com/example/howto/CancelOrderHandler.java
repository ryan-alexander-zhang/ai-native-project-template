package com.example.howto;

import com.aipersimmon.ddd.cqrs.CommandHandler;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Handles {@link CancelOrder}: sets the order to CANCELLED (the compensating action). */
@Component
public class CancelOrderHandler implements CommandHandler<CancelOrder, Void> {

    private final JdbcTemplate jdbc;

    public CancelOrderHandler(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Void handle(CancelOrder command) {
        jdbc.update("UPDATE orders SET status = 'CANCELLED' WHERE id = ?", command.orderId());
        return null;
    }
}
