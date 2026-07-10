package com.example.howto;

import com.aipersimmon.ddd.application.IntegrationEvents;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Places a reservation and announces it. The business insert and the outbox write
 * (via {@link IntegrationEvents}, backed by the outbox writer) run in one
 * transaction, so the event is stored durably with the change; the relay ships it
 * to Kafka afterwards.
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
    public void reserve(String reservationId, String sku) {
        jdbc.update("INSERT INTO reservation (id, sku) VALUES (?, ?)", reservationId, sku);
        integrationEvents.publish(new ReservationPlaced(reservationId, sku));
    }
}
