package com.example.samples.s20.ordering.infrastructure;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.samples.s20.ordering.application.OrderFilter;
import com.example.samples.s20.ordering.application.OrderQueries;
import com.example.samples.s20.ordering.application.OrderSort;
import com.example.samples.s20.ordering.application.OrderSummary;
import com.example.samples.s20.ordering.application.PageCursor;
import java.util.List;
import org.springframework.stereotype.Repository;

/**
 * The read side's SQL, and nothing else: no cursor encoding, no {@code +1} arithmetic, no choice of
 * result shape. It answers "these rows, in this order, after this one, at most this many".
 *
 * <p>It never loads an {@code Order}. An aggregate exists to protect writes; rebuilding one per row
 * to render a list pays for invariants the list will not use.
 */
@Repository
class MyBatisOrderQueries implements OrderQueries {

  /**
   * The seek predicate, as a row-value comparison.
   *
   * <p>PostgreSQL compares tuples left to right, so {@code (placed_at, id) < (?, ?)} is exactly
   * "strictly earlier in this ordering" — and it is a predicate the index on {@code (placed_at DESC,
   * id DESC)} can serve as one range scan. Written out by hand it becomes {@code placed_at < ? OR
   * (placed_at = ? AND id < ?)}, which is the same thing said less clearly and, in many planners,
   * executed worse.
   *
   * <p>The timestamp arrives as an ISO-8601 string with an explicit cast rather than a bound {@code
   * Instant}: the cast says which type the comparison happens in, instead of leaving it to how a
   * driver chose to bind the parameter and which session time zone was in force.
   */
  private static final String SEEK_BACKWARD = "(placed_at, id) < (CAST({0} AS timestamptz), {1})";

  private static final String SEEK_FORWARD = "(placed_at, id) > (CAST({0} AS timestamptz), {1})";

  private final OrderMapper mapper;

  MyBatisOrderQueries(OrderMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public List<OrderSummary> fetch(
      OrderFilter filter, OrderSort sort, int limit, PageCursor after) {
    LambdaQueryWrapper<OrderRow> wrapper = matching(filter);
    if (after != null) {
      wrapper.apply(
          sort.descending() ? SEEK_BACKWARD : SEEK_FORWARD,
          after.placedAt().toString(),
          after.orderId());
    }
    if (sort.descending()) {
      wrapper.orderByDesc(OrderRow::getPlacedAt).orderByDesc(OrderRow::getId);
    } else {
      wrapper.orderByAsc(OrderRow::getPlacedAt).orderByAsc(OrderRow::getId);
    }
    // MyBatis-Plus has a Page of its own, unrelated to the library's; page 1 with the count
    // suppressed is how one asks it for a LIMIT and nothing else. The offset stays 0 forever —
    // seeking is the cursor's job.
    com.baomidou.mybatisplus.extension.plugins.pagination.Page<OrderRow> window =
        new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, limit, false);
    return mapper.selectPage(window, wrapper).getRecords().stream()
        .map(MyBatisOrderQueries::toSummary)
        .toList();
  }

  @Override
  public long count(OrderFilter filter) {
    return mapper.selectCount(matching(filter));
  }

  /**
   * The filter, as predicates that exist only when the filter has something to say.
   *
   * <p>The conditional {@code eq(boolean, ...)} overloads are what keep this from becoming string
   * building. Nothing here concatenates a fragment a caller supplied, so there is no injection
   * surface to review and no {@code if} ladder that quietly forgets a clause.
   */
  private LambdaQueryWrapper<OrderRow> matching(OrderFilter filter) {
    return new LambdaQueryWrapper<OrderRow>()
        .eq(filter.customerId() != null, OrderRow::getCustomerId, filter.customerId())
        .eq(
            filter.status() != null,
            OrderRow::getStatus,
            filter.status() == null ? null : filter.status().name());
  }

  static OrderSummary toSummary(OrderRow row) {
    return new OrderSummary(
        row.getId(), row.getCustomerId(), row.getStatus(), row.getQuantity(), row.getPlacedAt());
  }
}
