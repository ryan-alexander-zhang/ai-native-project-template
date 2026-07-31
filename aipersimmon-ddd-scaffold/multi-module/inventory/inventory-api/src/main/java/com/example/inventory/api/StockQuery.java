package com.example.inventory.api;

import java.util.List;

/**
 * Request DTO of {@link StockAvailabilityApi}: the SKUs — and the quantities of each — whose
 * current offerability the caller wants to know. Part of the inventory context's published language
 * — a flat, serialisable carrier, independent of both the caller's model and inventory's internal
 * types, so it survives the move from an in-process call to an HTTP payload unchanged.
 *
 * <p>The quantity is what makes the answer worth asking for (issue-00150): the previous shape
 * carried SKUs alone, so the gate could only answer "is any on hand?" — a 999-unit order sailed
 * through against a stock of 5 and was placed only to walk the whole compensation circle. Asking
 * with the quantity turns the same synchronous check into "can you offer <em>this many</em>?".
 * Lines repeating a SKU are summed by the answering side, so the verdict is about the caller's
 * total demand.
 */
public record StockQuery(List<Line> lines) {

  public StockQuery {
    // Refused at parse time (issue-00143): a null list is a malformed request, not a question
    // inventory can answer. An empty list stays legal — degenerate, but with a well-defined
    // (empty) answer. The copy also keeps this carrier immutable.
    if (lines == null) {
      throw new IllegalArgumentException("lines required");
    }
    lines = List.copyOf(lines);
  }

  /** One asked-about SKU and how many of it the caller wants. */
  public record Line(String sku, int quantity) {
    public Line {
      Contract.required(sku, "sku");
      if (quantity < 1) {
        throw new IllegalArgumentException("quantity must be at least 1, got " + quantity);
      }
    }
  }
}
