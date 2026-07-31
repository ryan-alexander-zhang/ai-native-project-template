package com.example.inventory.api;

import java.util.List;

/**
 * Request DTO of {@link StockAvailabilityApi}: the SKUs whose current offerability the caller wants
 * to know. Part of the inventory context's published language — a flat, serialisable carrier,
 * independent of both the caller's model and inventory's internal types, so it survives the move
 * from an in-process call to an HTTP payload unchanged.
 */
public record StockQuery(List<String> skus) {

  public StockQuery {
    // Refused at parse time (issue-00143): a null list or a blank SKU is a malformed request,
    // not a question inventory can answer. An empty list stays legal — degenerate, but with a
    // well-defined (empty) answer. The copy also keeps this carrier immutable.
    if (skus == null) {
      throw new IllegalArgumentException("skus required");
    }
    skus = List.copyOf(skus);
    for (String sku : skus) {
      Contract.required(sku, "sku");
    }
  }
}
