package com.aipersimmon.ddd.web.page;

import java.util.List;

/**
 * A page of results that also reports totals — the offset-compatible shape for the cases that
 * genuinely need a total count or page count. Prefer {@link Slice} when totals are not required,
 * since counting is often the expensive part of a query.
 *
 * @param items the elements of this page; never null (copied defensively)
 * @param nextCursor cursor to fetch the next page, or null when exhausted
 * @param totalElements total number of matching elements across all pages
 * @param totalPages total number of pages
 * @param <T> the element type
 */
public record Page<T>(List<T> items, Cursor nextCursor, long totalElements, int totalPages) {

  public Page {
    items = items == null ? List.of() : List.copyOf(items);
    if (totalElements < 0) {
      throw new IllegalArgumentException("totalElements must not be negative");
    }
    if (totalPages < 0) {
      throw new IllegalArgumentException("totalPages must not be negative");
    }
  }
}
