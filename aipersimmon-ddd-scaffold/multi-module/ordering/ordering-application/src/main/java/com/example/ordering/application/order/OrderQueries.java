package com.example.ordering.application.order;

import com.aipersimmon.ddd.cqrs.page.Cursor;
import com.aipersimmon.ddd.cqrs.page.Slice;

/**
 * The read side's own port. It is <em>not</em> a repository: {@code Orders} exists to load an
 * aggregate you intend to change, and rehydrating one to answer a query pays for invariants and
 * child collections a read never touches. This port answers from the tables directly, so the query
 * shape and the aggregate shape are free to differ — which is the whole reason the two sides are
 * separated.
 *
 * <p>It lives in the application layer rather than the domain for the same reason: a list of orders
 * is a use-case shape, not a piece of the model. The domain has no opinion about pages.
 */
public interface OrderQueries {

  /**
   * A customer's orders, newest first.
   *
   * @param customerId whose orders to list
   * @param after the cursor from the previous page, or null for the first page
   * @param size how many rows to return at most
   * @return the page, with a cursor when more rows remain
   */
  Slice<OrderListItem> byCustomer(String customerId, Cursor after, int size);
}
