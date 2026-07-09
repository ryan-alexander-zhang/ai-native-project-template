package com.example.howto;

import com.aipersimmon.ddd.application.Inbox;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A consumer that applies a reservation to a read view, made idempotent by the
 * inbox: keyed by the delivered event id, a redelivered message is skipped so the
 * effect is applied once. The inbox check and the effect share one transaction.
 */
@Service
public class ReservationProjection {

    private final JdbcTemplate jdbc;
    private final Inbox inbox;

    public ReservationProjection(JdbcTemplate jdbc, Inbox inbox) {
        this.jdbc = jdbc;
        this.inbox = inbox;
    }

    @Transactional
    public void apply(String eventId, String sku) {
        if (inbox.alreadyProcessed(eventId)) {
            return;
        }
        jdbc.update("INSERT INTO reservation_view (sku) VALUES (?)", sku);
    }
}
