package com.example.inventory.adapter.ipc;

import com.aipersimmon.ddd.cqrs.QueryBus;
import com.example.inventory.api.StockAvailabilityApi;
import com.example.inventory.api.StockAvailabilityReport;
import com.example.inventory.api.StockQuery;
import com.example.inventory.application.stock.CheckStockAvailability;
import com.example.inventory.application.stock.StockLevel;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Inbound (driving) adapter that implements the inventory context's {@link StockAvailabilityApi}
 * Open Host Service in-process. It is the query-side sibling of a REST controller: it receives a
 * call in the published contract's terms, translates it into the application's {@link
 * CheckStockAvailability} query, dispatches it through the {@link QueryBus}, and maps the read
 * model back to the published {@link StockAvailabilityReport}. The application and domain never see
 * the contract types — the provider-side anti-corruption mapping lives here.
 *
 * <p>Being an adapter, this class is exactly the seam that changes when inventory is extracted into
 * its own service: this in-process implementation is superseded by an HTTP endpoint (a
 * {@code @RestController}) that delegates to the same query. The contract, the application, and the
 * domain stay put — only the inbound adapter's protocol changes.
 */
@Component
public class StockAvailabilityService implements StockAvailabilityApi {

  private final QueryBus queryBus;

  public StockAvailabilityService(QueryBus queryBus) {
    this.queryBus = queryBus;
  }

  @Override
  public StockAvailabilityReport check(StockQuery query) {
    // Lines repeating a SKU are summed before the level is consulted (the contract says so): the
    // caller's total demand is what has to fit, and two lines of 3 against a stock of 5 must not
    // each pass alone.
    Map<String, Integer> requested = new LinkedHashMap<>();
    for (StockQuery.Line line : query.lines()) {
      requested.merge(line.sku(), line.quantity(), Integer::sum);
    }
    List<StockLevel> levels =
        queryBus.ask(new CheckStockAvailability(List.copyOf(requested.keySet())));
    List<StockAvailabilityReport.Item> items =
        levels.stream()
            .map(
                level ->
                    new StockAvailabilityReport.Item(
                        level.sku(), level.available() >= requested.get(level.sku())))
            .toList();
    return new StockAvailabilityReport(items);
  }
}
