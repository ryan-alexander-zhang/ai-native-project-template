package com.example.inventory.domain.stock;

import com.aipersimmon.ddd.core.exception.DomainException;
import com.aipersimmon.ddd.core.model.Identifier;

/** Identity of a {@link Reservation}: the handle by which a reservation is later released. */
public record ReservationId(String value) implements Identifier {

    public ReservationId {
        if (value == null || value.isBlank()) {
            throw new DomainException("reservation id required");
        }
    }
}
