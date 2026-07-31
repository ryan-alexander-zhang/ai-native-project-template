package com.example.inventory.domain.stock;

import com.aipersimmon.ddd.core.annotation.Repository;
import java.util.Optional;

/** Repository port for {@link Reservation}; implemented in the infrastructure layer. */
@Repository
public interface Reservations {

  void save(Reservation reservation);

  Optional<Reservation> findById(ReservationId id);

  /**
   * The reservation held for an order, released or not — "one order, one reservation" is a business
   * fact (the schema enforces it as a unique key), so this lookup is what lets a redelivered
   * reservation request find the work already done and re-announce it instead of holding stock a
   * second time.
   */
  Optional<Reservation> findByOrderId(String orderId);
}
