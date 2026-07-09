package com.example.howto;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * In-process consumer of {@link ReservationPlaced}. With the outbox's in-process
 * dispatcher enabled ({@code aipersimmon.ddd.outbox.dispatch=in-process}), the
 * relay republishes stored events, and this listener receives them and applies
 * them idempotently through the inbox-guarded projection. The reservation id is
 * used as the idempotency key.
 */
@Component
public class ReservationPlacedListener {

    private final ReservationProjection projection;

    public ReservationPlacedListener(ReservationProjection projection) {
        this.projection = projection;
    }

    @EventListener
    public void on(ReservationPlaced event) {
        projection.apply(event.reservationId(), event.sku());
    }
}
