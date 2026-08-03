package com.example.samples.s20.ordering.application;

import java.util.List;

/**
 * The read-side port. It lives in the application layer, not the domain: reading is not part of the
 * order's ubiquitous language, and keeping it out of {@code Orders} leaves the write port with only
 * what the invariants need.
 *
 * <p>Note what it does <em>not</em> return: no {@code Slice}, no {@code Page}, no {@code Cursor}. An
 * adapter's job here is rows in an order, from a position, with a ceiling. Which shape those rows
 * are wrapped in, how the "is there more" question is answered, and how a position is turned into a
 * token are one decision that belongs in one place — {@link OrderPager} — instead of being
 * re-derived by every storage backend that implements this interface.
 */
public interface OrderQueries {

  /**
   * Rows matching the filter, in the sort's order, starting strictly after the cursor's row.
   *
   * @param after the position to resume after, or null to start at the beginning
   * @param limit hard ceiling on rows returned; the caller asks for one more than it needs
   */
  List<OrderSummary> fetch(OrderFilter filter, OrderSort sort, int limit, PageCursor after);

  /**
   * How many rows match the filter, ignoring any cursor.
   *
   * <p>Separate from {@link #fetch} because it is a separate statement with a separate cost — the
   * expensive one. Keeping it a distinct method means the cheap shape cannot accidentally pay for
   * it.
   */
  long count(OrderFilter filter);
}
