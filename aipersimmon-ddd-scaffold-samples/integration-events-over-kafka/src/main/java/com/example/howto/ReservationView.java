package com.example.howto;

import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Applies a reservation to a read view. It handles {@link ReservationPlaced} as an
 * ordinary in-process event: the Kafka consumer bridge, after reading the message
 * from the broker and deduplicating it against the inbox, republishes the event in
 * process, and this {@code @EventListener} receives it. Redelivery is already
 * filtered by the bridge's inbox, so this handler does not repeat the effect.
 */
@Component
public class ReservationView {

    private final JdbcTemplate jdbc;

    public ReservationView(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener
    public void on(ReservationPlaced event) {
        jdbc.update("INSERT INTO reservation_view (reservation_id, sku) VALUES (?, ?)",
                event.reservationId(), event.sku());
    }
}
