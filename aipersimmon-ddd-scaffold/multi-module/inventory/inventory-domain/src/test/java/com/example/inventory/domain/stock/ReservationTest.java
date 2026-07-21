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

  private static Reservation reservation() {
    return new Reservation(ID, "order-1", Map.of(SKU, 2));
  }

  @Test
  void rejectsBlankOrderId() {
    assertThrows(DomainException.class, () -> new Reservation(ID, " ", Map.of(SKU, 2)));
  }

  @Test
  void rejectsNullOrderId() {
    assertThrows(DomainException.class, () -> new Reservation(ID, null, Map.of(SKU, 2)));
  }

  @Test
  void rejectsEmptyHeldLines() {
    assertThrows(DomainException.class, () -> new Reservation(ID, "order-1", Map.of()));
  }

  @Test
  void rejectsNullHeldLines() {
    assertThrows(DomainException.class, () -> new Reservation(ID, "order-1", null));
  }

  @Test
  void exposesIdOrderAndHeldLines() {
    Reservation reservation = reservation();

    assertSame(ID, reservation.id());
    assertEquals("order-1", reservation.orderId());
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
}
