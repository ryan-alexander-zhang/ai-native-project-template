package com.example.ordering.domain.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.core.exception.DomainException;
import org.junit.jupiter.api.Test;

class OrderEvidenceRefTest {

  private static final OrderId ORDER = new OrderId("order-1");
  private static final OrderId OTHER = new OrderId("order-2");

  @Test
  void belongsToIsTrueOnlyForTheNamedOrder() {
    StockReleaseRef ref = new StockReleaseRef("rel-1", ORDER);

    assertTrue(ref.belongsTo(ORDER));
    assertFalse(ref.belongsTo(OTHER));
  }

  @Test
  void reviewDecisionRefValidatesAndEquals() {
    assertThrows(DomainException.class, () -> new ReviewDecisionRef(" ", ORDER, true));
    assertThrows(DomainException.class, () -> new ReviewDecisionRef("d-1", null, true));

    ReviewDecisionRef ref = new ReviewDecisionRef("d-1", ORDER, true);
    assertEquals(new ReviewDecisionRef("d-1", ORDER, true), ref);
    assertNotEquals(new ReviewDecisionRef("d-2", ORDER, true), ref);
    assertTrue(ref.approved());
  }

  @Test
  void paymentDeclineRefValidates() {
    assertThrows(DomainException.class, () -> new PaymentDeclineRef(" ", ORDER, "c"));
    assertThrows(DomainException.class, () -> new PaymentDeclineRef("p-1", null, "c"));
    assertThrows(DomainException.class, () -> new PaymentDeclineRef("p-1", ORDER, " "));

    PaymentDeclineRef ref = new PaymentDeclineRef("p-1", ORDER, "card_declined");
    assertEquals("card_declined", ref.declineCode());
    assertTrue(ref.belongsTo(ORDER));
  }

  @Test
  void stockReleaseRefValidates() {
    assertThrows(DomainException.class, () -> new StockReleaseRef(" ", ORDER));
    assertThrows(DomainException.class, () -> new StockReleaseRef("r-1", null));

    assertEquals("r-1", new StockReleaseRef("r-1", ORDER).releaseId());
  }

  @Test
  void reservationFailureRefValidates() {
    assertThrows(DomainException.class, () -> new ReservationFailureRef(" ", ORDER, "c", "d"));
    assertThrows(DomainException.class, () -> new ReservationFailureRef("f-1", null, "c", "d"));
    assertThrows(DomainException.class, () -> new ReservationFailureRef("f-1", ORDER, " ", "d"));

    ReservationFailureRef ref = new ReservationFailureRef("f-1", ORDER, "out_of_stock", "SKU-1");
    assertTrue(ref.belongsTo(ORDER));
    assertEquals("out_of_stock", ref.reasonCode());
    assertEquals("SKU-1", ref.detail());
  }
}
