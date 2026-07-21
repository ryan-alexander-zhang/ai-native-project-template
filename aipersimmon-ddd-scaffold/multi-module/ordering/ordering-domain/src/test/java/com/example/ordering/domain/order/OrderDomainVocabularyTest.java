package com.example.ordering.domain.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.core.exception.DomainException;
import com.example.ordering.domain.customer.CustomerId;
import com.example.ordering.domain.shared.OrderingErrorCode;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OrderDomainVocabularyTest {

  private static final OrderId ORDER = new OrderId("order-1");

  @Test
  void orderIdRejectsNullOrBlank() {
    assertThrows(DomainException.class, () -> new OrderId(null));
    assertThrows(DomainException.class, () -> new OrderId(" "));
    assertEquals("order-1", ORDER.value());
  }

  @Test
  void cancellationCategoryMapsEveryReason() {
    assertSame(
        CancellationCategory.CUSTOMER_REQUESTED,
        CancellationCategory.from(new CancellationReason.CustomerRequested(new CustomerId("c"))));
    assertSame(
        CancellationCategory.INVENTORY_UNAVAILABLE,
        CancellationCategory.from(
            new CancellationReason.InventoryUnavailable(
                new ReservationFailureRef("f", ORDER, "code", "d"))));
    assertSame(
        CancellationCategory.PAYMENT_DECLINED,
        CancellationCategory.from(
            new CancellationReason.PaymentDeclinedAfterStockReleased(
                new PaymentDeclineRef("p", ORDER, "declined"), new StockReleaseRef("r", ORDER))));
    assertSame(
        CancellationCategory.REVIEW_REJECTED,
        CancellationCategory.from(
            new CancellationReason.ReviewRejected(new ReviewDecisionRef("rev", ORDER, false))));
  }

  @Test
  void cancellationReasonsRequireTheirEvidence() {
    assertThrows(DomainException.class, () -> new CancellationReason.CustomerRequested(null));
    assertThrows(DomainException.class, () -> new CancellationReason.InventoryUnavailable(null));
    assertThrows(DomainException.class, () -> new CancellationReason.ReviewRejected(null));
  }

  @Test
  void reviewRequirementNotRequired() {
    assertFalse(ReviewRequirement.notRequired().isRequired());
  }

  @Test
  void reviewRequirementRequiredHoldsReasonsAndIsRequired() {
    assertTrue(ReviewRequirement.required(Set.of("high_value")).isRequired());
  }

  @Test
  void reviewRequirementRequiredRejectsNoReasons() {
    assertThrows(DomainException.class, () -> ReviewRequirement.required(null));
    assertThrows(DomainException.class, () -> ReviewRequirement.required(Set.of()));
  }

  @Test
  void orderingErrorCodesAllCarryACodeAndCategory() {
    for (OrderingErrorCode code : OrderingErrorCode.values()) {
      assertNotNull(code.category(), code + " has a category");
      assertFalse(code.code().isBlank(), code + " has a non-blank code");
    }
    // Spot-check one exact mapping.
    assertEquals("ordering.credit-exceeded", OrderingErrorCode.CREDIT_EXCEEDED.code());
  }
}
