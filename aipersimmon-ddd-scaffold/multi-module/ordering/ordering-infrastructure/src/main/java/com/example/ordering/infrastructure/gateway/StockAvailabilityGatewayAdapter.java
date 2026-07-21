package com.example.ordering.infrastructure.gateway;

import com.example.inventory.api.StockAvailabilityApi;
import com.example.inventory.api.StockAvailabilityReport;
import com.example.inventory.api.StockQuery;
import com.example.ordering.application.order.StockAvailabilityGateway;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Driven adapter that implements ordering's {@link StockAvailabilityGateway} port by calling the
 * inventory context's {@link StockAvailabilityApi} Open Host Service. This is ordering's
 * <em>anti-corruption layer</em> toward inventory: the only place in the ordering context that
 * references inventory's published contract, and the place that translates between the two
 * contexts' languages (ordering's SKU list &harr; inventory's {@link StockQuery} / {@link
 * StockAvailabilityReport}). Ordering's domain and application stay free of any inventory type.
 *
 * <p>It depends on the {@code StockAvailabilityApi} <em>interface</em> only, never on an
 * implementation. In the modular monolith Spring injects inventory's in-process implementation
 * directly; when inventory becomes a separate service the same interface is satisfied by an HTTP
 * client bean instead — this adapter, the port, and every caller are unchanged. Routing the call
 * through a port and a contract (rather than a direct reference) is precisely what makes that swap
 * a wiring change, not a rewrite.
 */
@Component
public class StockAvailabilityGatewayAdapter implements StockAvailabilityGateway {

  private final StockAvailabilityApi stockAvailabilityApi;

  public StockAvailabilityGatewayAdapter(StockAvailabilityApi stockAvailabilityApi) {
    this.stockAvailabilityApi = stockAvailabilityApi;
  }

  @Override
  public Availability check(List<String> skus) {
    StockAvailabilityReport report = stockAvailabilityApi.check(new StockQuery(skus));
    List<String> unavailable =
        report.items().stream()
            .filter(item -> !item.available())
            .map(StockAvailabilityReport.Item::sku)
            .toList();
    return new Availability(unavailable.isEmpty(), unavailable);
  }
}
