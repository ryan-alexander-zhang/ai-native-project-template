package com.example.inventory.adapter.ipc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.cqrs.spring.RegistryQueryBus;
import com.example.inventory.api.StockAvailabilityReport;
import com.example.inventory.api.StockQuery;
import com.example.inventory.application.stock.CheckStockAvailabilityHandler;
import com.example.inventory.application.stock.StockLevel;
import com.example.inventory.application.stock.StockQueries;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The Open Host Service's verdict is about the quantity asked for (issue-00150). The previous
 * contract carried SKUs alone, so this gate could only answer "is any on hand?" — a 999-unit order
 * passed synchronously against a stock of 5, was placed, and then walked the entire compensation
 * circle a quantity-aware answer would have spared. Assembled from the real handler over the real
 * query bus with a stubbed read port, because what is under test is the verdict arithmetic, not the
 * wiring.
 */
class StockAvailabilityServiceTest {

  private final StockAvailabilityService service =
      new StockAvailabilityService(
          new RegistryQueryBus(
              List.of(new CheckStockAvailabilityHandler(levelsOf(Map.of("SKU-1", 5))))));

  @Test
  void aQuantityWithinTheLevelIsOfferable() {
    StockAvailabilityReport report =
        service.check(new StockQuery(List.of(new StockQuery.Line("SKU-1", 5))));

    assertTrue(report.items().get(0).available(), "5 of 5 is exactly offerable");
  }

  /** The case the SKU-only contract could not see: plenty of "any", not enough of "this many". */
  @Test
  void aQuantityBeyondTheLevelIsNotOfferableEvenThoughSomeIsOnHand() {
    StockAvailabilityReport report =
        service.check(new StockQuery(List.of(new StockQuery.Line("SKU-1", 999))));

    assertFalse(
        report.items().get(0).available(),
        "999 against a stock of 5 must be refused here, synchronously — not placed and then"
            + " compensated");
  }

  /** Two lines of 3 against a stock of 5: each fits alone, the demand does not. */
  @Test
  void linesRepeatingASkuAreSummedBeforeTheVerdict() {
    StockAvailabilityReport report =
        service.check(
            new StockQuery(
                List.of(new StockQuery.Line("SKU-1", 3), new StockQuery.Line("SKU-1", 3))));

    assertEquals(1, report.items().size(), "one verdict per distinct SKU");
    assertFalse(report.items().get(0).available());
  }

  @Test
  void anUnknownSkuIsNotOfferable() {
    StockAvailabilityReport report =
        service.check(new StockQuery(List.of(new StockQuery.Line("SKU-MISSING", 1))));

    assertFalse(report.items().get(0).available(), "a level of 0 offers nothing");
  }

  /** The read port answers a level per asked SKU, 0 for anything not carried — as the real one. */
  private static StockQueries levelsOf(Map<String, Integer> levels) {
    return skus ->
        skus.stream().map(sku -> new StockLevel(sku, levels.getOrDefault(sku, 0))).toList();
  }
}
