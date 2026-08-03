package com.example.samples.s20.ordering.application;

/**
 * The orderings this endpoint serves — a closed set, which is the whole mechanism.
 *
 * <p>A client that sends a column name (or a column name and a direction) is a client that can send
 * anything, and the adapter then either forwards a string into SQL or reinvents this enum with a
 * hand-written whitelist. Binding a request parameter to an enum makes the whitelist the type
 * system's job: an unknown value never reaches a query, let alone a statement.
 *
 * <p>Each ordering names a <em>total</em> sort key — a business column plus the id. Totality is not
 * decoration: two rows that compare equal under the sort key have no defined order between them, so
 * a cursor pointing "after" one of them cannot say which side of the tie it is on, and pages start
 * duplicating and skipping rows. The id breaks every tie because it is unique and never changes.
 */
public enum OrderSort {
  /** Newest first: what a human expects from a list of their own orders. */
  NEWEST_FIRST(true),
  /** Oldest first: what a batch job or an export wants, so it can resume where it stopped. */
  OLDEST_FIRST(false);

  private final boolean descending;

  OrderSort(boolean descending) {
    this.descending = descending;
  }

  /** Whether the sort key runs downwards; the seek predicate flips with it. */
  public boolean descending() {
    return descending;
  }
}
