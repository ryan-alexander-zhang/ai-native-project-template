package com.example.howto;

import com.aipersimmon.ddd.integration.IntegrationEvent;

/** Integration event announced when a reservation is placed. */
public record ReservationPlaced(String reservationId, String sku) implements IntegrationEvent {
}
