package com.example.samples.s20.ordering.application;

import com.aipersimmon.ddd.application.ApplicationException;
import com.aipersimmon.ddd.cqrs.page.Cursor;
import com.example.samples.s20.ordering.domain.OrderingErrorCode;

/**
 * One read request: what to filter, how to order, how many, and where to resume. Both list queries
 * take this same value and differ only in what they return.
 *
 * <p>The size bound lives <em>here</em>, in the query contract, and not only on the HTTP parameter.
 * The command bus validates every command it dispatches; the query bus deliberately ships no
 * interceptors at all, so nothing between a caller and a query handler will check anything on a
 * query's behalf. A read contract enforced only at the web edge is a read contract that the
 * scheduled export, the admin tool and the next adapter do not have — and an unbounded list is not
 * a cosmetic problem: it is one request that reads the whole table.
 *
 * @param filter which orders; never null after construction
 * @param sort which ordering; never null after construction
 * @param size how many rows the caller wants back
 * @param cursor where to resume, or null for the first page
 */
public record PageRequest(OrderFilter filter, OrderSort sort, int size, Cursor cursor) {

  /** What a client gets by not saying. */
  public static final int DEFAULT_SIZE = 20;

  /** The ceiling. Chosen, written down, and enforced on every entry rather than at one of them. */
  public static final int MAX_SIZE = 200;

  public PageRequest {
    filter = filter == null ? OrderFilter.unfiltered() : filter;
    sort = sort == null ? OrderSort.NEWEST_FIRST : sort;
    if (size < 1 || size > MAX_SIZE) {
      throw new ApplicationException(
          OrderingErrorCode.PAGE_SIZE_OUT_OF_RANGE,
          "page size must be between 1 and " + MAX_SIZE + ", was " + size);
    }
  }

  /** The first page of an unfiltered, newest-first list. */
  public static PageRequest firstPage(int size) {
    return new PageRequest(OrderFilter.unfiltered(), OrderSort.NEWEST_FIRST, size, null);
  }

  /** The same request, resumed at the given cursor. */
  public PageRequest resumedAt(Cursor next) {
    return new PageRequest(filter, sort, size, next);
  }
}
