package com.example.ordering.domain.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.core.exception.DomainException;
import com.example.ordering.domain.shared.Money;
import com.example.ordering.domain.shared.OrderingErrorCode;
import java.util.List;
import org.junit.jupiter.api.Test;

/** OrderLine and OrderHasDistinctSkus are package-private, so this test lives in their package. */
class OrderLineAndInvariantTest {

  private static OrderLine line(String sku, int qty) {
    return new OrderLine(sku, qty, Money.of(1_000, "USD"));
  }

  @Test
  void orderLineExposesItsFieldsAndComputesSubtotal() {
    OrderLine line = line("SKU-1", 3);

    assertEquals("SKU-1", line.sku());
    assertEquals(3, line.quantity());
    assertEquals(Money.of(1_000, "USD"), line.unitPrice());
    assertEquals(Money.of(3_000, "USD"), line.subtotal());
  }

  @Test
  void orderLineRejectsBlankSkuAndNonPositiveQuantity() {
    assertThrows(DomainException.class, () -> new OrderLine(" ", 1, Money.of(1, "USD")));
    assertThrows(DomainException.class, () -> new OrderLine(null, 1, Money.of(1, "USD")));
    assertThrows(DomainException.class, () -> new OrderLine("SKU-1", 0, Money.of(1, "USD")));
  }

  @Test
  void distinctSkusInvariantHoldsForDistinctLines() {
    OrderHasDistinctSkus invariant =
        new OrderHasDistinctSkus(List.of(line("SKU-1", 1), line("SKU-2", 1)));

    assertFalse(invariant.isBroken());
  }

  @Test
  void distinctSkusInvariantIsBrokenByARepeatedSku() {
    OrderHasDistinctSkus invariant =
        new OrderHasDistinctSkus(List.of(line("SKU-1", 1), line("SKU-1", 2)));

    assertTrue(invariant.isBroken());
    assertSame(OrderingErrorCode.DUPLICATE_SKU, invariant.errorCode());
    assertEquals("an order must not repeat a SKU across lines", invariant.message());
  }

  @Test
  void distinctSkusInvariantTreatsNullLinesAsNotBroken() {
    assertFalse(new OrderHasDistinctSkus(null).isBroken());
  }
}
