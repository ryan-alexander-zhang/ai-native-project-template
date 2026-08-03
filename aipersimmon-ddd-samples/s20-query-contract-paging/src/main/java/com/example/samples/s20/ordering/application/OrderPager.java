package com.example.samples.s20.ordering.application;

import com.aipersimmon.ddd.cqrs.page.Cursor;
import com.aipersimmon.ddd.cqrs.page.Page;
import com.aipersimmon.ddd.cqrs.page.Slice;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Turns rows into the library's two list shapes. Both go through {@link #window}, so the two
 * endpoints cannot drift apart in what "the next page" means.
 */
@Component
class OrderPager {

  private final OrderQueries queries;

  OrderPager(OrderQueries queries) {
    this.queries = queries;
  }

  /** The default shape: items and a cursor, no count. */
  Slice<OrderSummary> slice(PageRequest request) {
    Window window = window(request);
    return new Slice<>(window.items(), window.nextCursor());
  }

  /**
   * The same rows, plus totals — and plus a second statement to get them.
   *
   * <p>Totals are the reason to reach for {@code Page}, and the reason not to: the count scans every
   * matching row while the slice reads at most {@code size + 1} of them, so on a large filtered set
   * the count is the query. Offer this where a human genuinely reads the number, not by default.
   */
  Page<OrderSummary> page(PageRequest request) {
    Window window = window(request);
    long total = queries.count(request.filter());
    int totalPages = (int) Math.ceilDiv(total, request.size());
    return new Page<>(window.items(), window.nextCursor(), total, totalPages);
  }

  /**
   * One page, and the answer to "is there another".
   *
   * <p>The trick is the {@code + 1}: ask for one row more than the client wants, and the extra row's
   * existence answers the question that a count would otherwise have to. Getting this wrong is the
   * classic list bug — returning a cursor whenever a page came back full hands the client a token
   * that leads to an empty page, and every client that renders "next" from the cursor's presence
   * shows a button that goes nowhere.
   *
   * <p>The cursor is minted from the last <em>returned</em> row, never from the extra one. The extra
   * row is evidence, not content: it will be the first row of the next page.
   */
  private Window window(PageRequest request) {
    PageCursor after =
        request.cursor() == null
            ? null
            : PageCursor.decode(
                request.cursor(), request.sort(), request.filter().fingerprint());

    List<OrderSummary> rows =
        queries.fetch(request.filter(), request.sort(), request.size() + 1, after);

    boolean hasMore = rows.size() > request.size();
    List<OrderSummary> items = hasMore ? List.copyOf(rows.subList(0, request.size())) : rows;
    if (items.isEmpty() || !hasMore) {
      return new Window(items, null);
    }
    OrderSummary last = items.get(items.size() - 1);
    Cursor next =
        new PageCursor(
                request.sort(), request.filter().fingerprint(), last.placedAt(), last.id())
            .encode();
    return new Window(items, next);
  }

  private record Window(List<OrderSummary> items, Cursor nextCursor) {}
}
