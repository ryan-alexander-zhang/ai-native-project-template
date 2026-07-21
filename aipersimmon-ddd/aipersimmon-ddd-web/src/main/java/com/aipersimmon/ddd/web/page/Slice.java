package com.aipersimmon.ddd.web.page;

import java.util.List;

/**
 * A cursor-based page of results: the {@code items} plus an optional {@code nextCursor}. It carries
 * no total count — the default, cheapest shape, and the one large APIs favour. A null {@code
 * nextCursor} means there is no next page.
 *
 * @param items the elements of this page; never null (copied defensively)
 * @param nextCursor cursor to fetch the next page, or null when exhausted
 * @param <T> the element type
 */
public record Slice<T>(List<T> items, Cursor nextCursor) {

  public Slice {
    items = items == null ? List.of() : List.copyOf(items);
  }

  /** Whether a further page exists. */
  public boolean hasNext() {
    return nextCursor != null;
  }
}
