package com.example.inventory.infrastructure.persistence;

import com.example.inventory.domain.stock.Reservation;
import com.example.inventory.domain.stock.ReservationId;
import com.example.inventory.domain.stock.Reservations;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

/** In-memory {@link Reservations} implementation, keyed by reservation id. */
@Repository
public class InMemoryReservations implements Reservations {

    private final Map<String, Reservation> store = new ConcurrentHashMap<>();

    @Override
    public void save(Reservation reservation) {
        store.put(reservation.id().value(), reservation);
    }

    @Override
    public Optional<Reservation> findById(ReservationId id) {
        return Optional.ofNullable(store.get(id.value()));
    }
}
