package com.example.inventory.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.inventory.application.stock.StockLevel;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The part of the batch read that a single query cannot do for itself.
 *
 * <p>{@code StockLevel} promises a row per requested SKU, with {@code available = 0} for anything
 * not carried, so a caller cannot tell an unknown SKU from an out-of-stock one. The
 * one-query-per-SKU implementation got that for free — every SKU had its own lookup. An {@code IN}
 * query returns nothing for absent SKUs, so the batch version has to fill them back in, and
 * forgetting to would quietly change the published behaviour from "zero" to "missing". That is what
 * this pins.
 *
 * <p>The SQL itself needs no test of its own here: {@code PlaceOrder} consults this read on every
 * placement, so every acceptance test in {@code start} exercises the statement end to end.
 */
class MyBatisStockQueriesTest {

  @Test
  void everyRequestedSkuGetsARowAndUnknownOnesReportZero() {
    MyBatisStockQueries queries = new MyBatisStockQueries(stubReturning("SKU-1", 7, "SKU-2", 3));

    List<StockLevel> levels = queries.levelsOf(List.of("SKU-1", "SKU-MISSING", "SKU-2"));

    assertEquals(
        List.of(
            new StockLevel("SKU-1", 7),
            new StockLevel("SKU-MISSING", 0),
            new StockLevel("SKU-2", 3)),
        levels,
        "a row per requested SKU, in the order asked, with 0 standing in for 'not carried'");
  }

  @Test
  void aRepeatedSkuIsQueriedOnceAndAnsweredEachTimeItWasAsked() {
    CountingStub stub = stubReturning("SKU-1", 5);
    MyBatisStockQueries queries = new MyBatisStockQueries(stub);

    List<StockLevel> levels = queries.levelsOf(List.of("SKU-1", "SKU-1"));

    assertEquals(List.of("SKU-1"), stub.asked, "the query does not repeat a SKU");
    assertEquals(
        List.of(new StockLevel("SKU-1", 5), new StockLevel("SKU-1", 5)),
        levels,
        "but the caller still gets an answer per line it asked about");
  }

  @Test
  void nothingAskedIsNoQueryAtAll() {
    CountingStub stub = stubReturning();
    MyBatisStockQueries queries = new MyBatisStockQueries(stub);

    assertEquals(List.of(), queries.levelsOf(List.of()));
    assertEquals(0, stub.calls, "an empty IN list is not valid SQL, and not worth a round trip");
  }

  private static CountingStub stubReturning(Object... skuThenAvailable) {
    CountingStub stub = new CountingStub();
    for (int i = 0; i < skuThenAvailable.length; i += 2) {
      StockLevelRow row = new StockLevelRow();
      row.setSku((String) skuThenAvailable[i]);
      row.setAvailable((Integer) skuThenAvailable[i + 1]);
      stub.rows.add(row);
    }
    return stub;
  }

  /** Records what the mapper was asked for, so "one query" is asserted rather than assumed. */
  private static final class CountingStub implements StockLevelMapper {
    private final List<StockLevelRow> rows = new ArrayList<>();
    private final List<String> asked = new ArrayList<>();
    private int calls;

    @Override
    public List<StockLevelRow> levelsOf(List<String> skus) {
      calls++;
      asked.addAll(skus);
      return rows.stream().filter(row -> skus.contains(row.getSku())).toList();
    }
  }
}
