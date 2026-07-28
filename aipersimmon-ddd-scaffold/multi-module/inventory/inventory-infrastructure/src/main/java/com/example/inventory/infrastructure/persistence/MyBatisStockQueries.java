package com.example.inventory.infrastructure.persistence;

import com.example.inventory.application.stock.StockLevel;
import com.example.inventory.application.stock.StockQueries;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Answers stock levels from {@link StockLevelMapper} in one query.
 *
 * <p>A {@code @Component}, not a {@code @Repository}: the stereotype marks an implementation of a
 * domain repository port, and this implements neither a domain port nor a repository. It is the
 * read side, which the aggregate boundary does not govern — the same reasoning {@code
 * MyBatisOrderQueries} records on the ordering side.
 *
 * <p>The filling-in below is the contract, not a convenience. {@code StockLevel} promises a row per
 * requested SKU with {@code available = 0} for anything not carried, so a caller cannot distinguish
 * an unknown SKU from an out-of-stock one. An {@code IN} query returns nothing for absent SKUs, so
 * dropping them would silently change that published behaviour from "zero" to "missing".
 */
@Component
public class MyBatisStockQueries implements StockQueries {

  private final StockLevelMapper levels;

  public MyBatisStockQueries(StockLevelMapper levels) {
    this.levels = levels;
  }

  @Override
  public List<StockLevel> levelsOf(List<String> skus) {
    if (skus == null || skus.isEmpty()) {
      return List.of();
    }
    Map<String, Integer> found = new LinkedHashMap<>();
    for (StockLevelRow row : levels.levelsOf(skus.stream().distinct().toList())) {
      found.put(row.getSku(), row.getAvailable());
    }
    List<StockLevel> answer = new ArrayList<>(skus.size());
    for (String sku : skus) {
      answer.add(new StockLevel(sku, found.getOrDefault(sku, 0)));
    }
    return List.copyOf(answer);
  }
}
