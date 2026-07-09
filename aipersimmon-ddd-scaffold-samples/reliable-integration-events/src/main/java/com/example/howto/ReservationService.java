package com.example.howto;

import com.aipersimmon.ddd.application.IntegrationEvents;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Places a reservation and announces it. The business insert and the outbox write
 * (via {@link IntegrationEvents}) run in one transaction, so they commit or roll
 * back together — the point of the transactional outbox.
 */
@Service
public class ReservationService {

    private final JdbcTemplate jdbc;
    private final IntegrationEvents integrationEvents;

    public ReservationService(JdbcTemplate jdbc, IntegrationEvents integrationEvents) {
        this.jdbc = jdbc;
        this.integrationEvents = integrationEvents;
    }

    @Transactional
    public void reserve(String reservationId, String sku, boolean forceFailure) {
        jdbc.update("INSERT INTO reservation (id, sku) VALUES (?, ?)", reservationId, sku);
        integrationEvents.publish(new ReservationPlaced(reservationId, sku));
        if (forceFailure) {
            // Both the reservation row and the outbox row roll back with this transaction.
            throw new IllegalStateException("simulated failure after publishing");
        }
    }
}
