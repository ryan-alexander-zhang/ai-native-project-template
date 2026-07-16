package com.example.inventory.domain.stock;

import com.aipersimmon.ddd.core.annotation.Repository;
import java.util.Optional;

/** Repository port for {@link Reservation}; implemented in the infrastructure layer. */
@Repository
public interface Reservations {

    void save(Reservation reservation);

    Optional<Reservation> findById(ReservationId id);
}
