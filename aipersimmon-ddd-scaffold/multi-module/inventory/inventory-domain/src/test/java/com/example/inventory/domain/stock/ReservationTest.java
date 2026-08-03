package com.example.inventory.domain.stock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.core.exception.DomainException;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReservationTest {

  private static final ReservationId ID = new ReservationId("r-1");
  private static final Sku SKU = new Sku("sku-1");
  private static final OrderRef ORDER = new OrderRef("order-1");

  private static Reservation reservation() {
    return new Reservation(ID, ORDER, Map.of(SKU, 2));
  }

  @Test
  void rejectsNullOrderRef() {
    assertThrows(DomainException.class, () -> new Reservation(ID, null, Map.of(SKU, 2)));
  }

  @Test
  void rejectsANullId() {
    assertThrows(DomainException.class, () -> new Reservation(null, ORDER, Map.of(SKU, 2)));
  }

  /**
   * A held quantity of zero or less is not a smaller hold, it is corrupt state: it would be
   * persisted without complaint and only explode two transactions later, when the release hands
   * "-5" to Stock.release in a different aggregate.
   */
  @Test
  void rejectsANonPositiveHeldQuantity() {
    assertThrows(DomainException.class, () -> new Reservation(ID, ORDER, Map.of(SKU, 0)));
    assertThrows(DomainException.class, () -> new Reservation(ID, ORDER, Map.of(SKU, -5)));
  }

  @Test
  void rejectsEmptyHeldLines() {
    assertThrows(DomainException.class, () -> new Reservation(ID, ORDER, Map.of()));
  }

  @Test
  void rejectsNullHeldLines() {
    assertThrows(DomainException.class, () -> new Reservation(ID, ORDER, null));
  }

  @Test
  void exposesIdOrderAndHeldLines() {
    Reservation reservation = reservation();

    assertSame(ID, reservation.id());
    assertEquals(ORDER, reservation.orderId());
    assertEquals(1, reservation.held().size());
    assertEquals(SKU, reservation.held().get(0).getKey());
    assertEquals(2, reservation.held().get(0).getValue());
  }

  @Test
  void startsNotReleased() {
    assertFalse(reservation().isReleased());
  }

  @Test
  void markReleasedFlipsOnceThenIsIdempotent() {
    Reservation reservation = reservation();

    assertTrue(reservation.markReleased(), "first release takes effect");
    assertTrue(reservation.isReleased());
    assertFalse(reservation.markReleased(), "a second release is a no-op");
  }

  @Test
  void reconstituteRestoresTheReleasedFlagAndVersionWithoutReplayingBehaviour() {
    Reservation released =
        Reservation.reconstitute(
            new ReservationId("res-1"),
            new OrderRef("order-1"),
            Map.of(new Sku("sku-1"), 2),
            true,
            5L);

    assertTrue(released.isReleased(), "a released reservation loads back as released");
    assertFalse(released.markReleased(), "so releasing again is still a no-op");
    assertEquals(
        5L, released.version(), "the loaded version is what the repository checks on save");
    assertTrue(released.domainEvents().isEmpty(), "reconstitution records no events");
  }

  @Test
  void reconstituteAnUnreleasedReservation() {
    Reservation open =
        Reservation.reconstitute(
            new ReservationId("res-2"),
            new OrderRef("order-2"),
            Map.of(new Sku("sku-1"), 1),
            false,
            1L);

    assertFalse(open.isReleased());
    assertTrue(open.markReleased(), "an unreleased reservation can still be released");
  }
}
