package com.example.inventory.api;

import java.util.List;

/**
 * Response DTO of {@link StockAvailabilityApi}: for each requested SKU, whether inventory can
 * currently offer it. A published-language type — it exposes a boolean verdict per SKU, not
 * inventory's internal stock levels or aggregates, so the exact quantity on hand stays an inventory
 * secret.
 */
public record StockAvailabilityReport(List<Item> items) {

  public StockAvailabilityReport {
    // Refused at parse time (issue-00143): a report is a list of verdicts, and "no list" is not
    // the same statement as "no verdicts". The copy also keeps this carrier immutable.
    if (items == null) {
      throw new IllegalArgumentException("items required");
    }
    items = List.copyOf(items);
  }

  /** The verdict for one SKU: {@code available} is true when inventory can offer it now. */
  public record Item(String sku, boolean available) {
    public Item {
      Contract.required(sku, "sku");
    }
  }
}
