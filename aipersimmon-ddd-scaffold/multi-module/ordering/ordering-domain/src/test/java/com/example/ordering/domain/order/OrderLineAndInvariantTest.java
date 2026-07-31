package com.example.ordering.domain.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.core.exception.DomainException;
import com.example.ordering.domain.shared.Money;
import com.example.ordering.domain.shared.OrderingErrorCode;
import com.example.ordering.domain.shared.Sku;
import java.util.List;
import org.junit.jupiter.api.Test;

/** OrderLine and OrderHasDistinctSkus are package-private, so this test lives in their package. */
class OrderLineAndInvariantTest {

  private static OrderLine line(String sku, int qty) {
    return new OrderLine(new Sku(sku), qty, Money.of(1_000, "USD"));
  }

  @Test
  void orderLineExposesItsFieldsAndComputesSubtotal() {
    OrderLine line = line("SKU-1", 3);

    assertEquals(new Sku("SKU-1"), line.sku());
    assertEquals(3, line.quantity());
    assertEquals(Money.of(1_000, "USD"), line.unitPrice());
    assertEquals(Money.of(3_000, "USD"), line.subtotal());
  }

  @Test
  void orderLineRejectsBlankSkuAndNonPositiveQuantity() {
    // The blank rejection now happens in Sku's own constructor rather than OrderLine's — one
    // definition of "a SKU is not blank", which is the point of the type (issue-00085).
    assertThrows(DomainException.class, () -> new Sku(" "));
    assertThrows(DomainException.class, () -> new OrderLine(null, 1, Money.of(1, "USD")));
    assertThrows(
        DomainException.class, () -> new OrderLine(new Sku("SKU-1"), 0, Money.of(1, "USD")));
  }

  @Test
  void orderLineRejectsANullUnitPrice() {
    // sku and quantity are guarded; a null unitPrice used to walk in and NPE later in subtotal(),
    // far from the constructor that accepted it (issue-00145 item 2).
    assertThrows(DomainException.class, () -> new OrderLine(new Sku("SKU-1"), 1, null));
  }

  @Test
  void singleCurrencyInvariantHoldsWhenEveryLineAgrees() {
    OrderHasSingleCurrency invariant =
        new OrderHasSingleCurrency(List.of(line("SKU-1", 1), line("SKU-2", 1)));

    assertFalse(invariant.isBroken());
  }

  /**
   * The rule existed only as an arithmetic side effect: total() reducing mixed-currency lines
   * tripped Money.requireSameCurrency with a codeless "currency mismatch". A rule the aggregate
   * enforces deserves a name and a code of its own (issue-00145 item 3).
   */
  @Test
  void singleCurrencyInvariantIsBrokenByAMixedCurrencyOrder() {
    OrderHasSingleCurrency invariant =
        new OrderHasSingleCurrency(
            List.of(line("SKU-1", 1), new OrderLine(new Sku("SKU-2"), 1, Money.of(500, "EUR"))));

    assertTrue(invariant.isBroken());
    assertSame(OrderingErrorCode.MIXED_CURRENCY, invariant.errorCode());
    assertEquals("an order's lines must share a single currency", invariant.message());
  }

  @Test
  void singleCurrencyInvariantTreatsNullLinesAsNotBroken() {
    assertFalse(new OrderHasSingleCurrency(null).isBroken());
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
