package com.example.ordering.application.order;

import com.aipersimmon.ddd.cqrs.Query;
import com.aipersimmon.ddd.cqrs.page.Cursor;
import com.aipersimmon.ddd.cqrs.page.Slice;

/**
 * List one customer's orders, newest first, a page at a time.
 *
 * <p>Cursor paging rather than offset paging: an offset re-scans everything before it and shifts
 * under concurrent inserts, so page 2 can repeat or skip an order that arrived in between. A cursor
 * names a position instead, and here it can be the plain order id — ids are UUIDv7, so their
 * lexicographic order <em>is</em> their creation order. The time-ordered id decision pays for
 * itself twice: once in index locality on insert, once in not needing a separate sort key here.
 *
 * <p>Worth being precise about what the cursor buys, because it is easy to over-claim: it removes
 * the re-scan and the drift, but only an index makes a page cost the page rather than the table.
 * Both halves are real work — see {@code OrderListMapper} and the {@code V4} migration.
 *
 * @param customerId whose orders to list
 * @param cursor position from the previous page; null for the first page
 * @param size page size; the handler clamps it
 */
public record FindCustomerOrders(String customerId, Cursor cursor, int size)
    implements Query<Slice<OrderListItem>> {}
