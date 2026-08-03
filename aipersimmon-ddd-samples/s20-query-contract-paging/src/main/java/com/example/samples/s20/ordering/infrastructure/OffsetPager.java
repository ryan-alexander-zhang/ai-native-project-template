package com.example.samples.s20.ordering.infrastructure;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.samples.s20.ordering.application.OrderFilter;
import com.example.samples.s20.ordering.application.OrderSort;
import com.example.samples.s20.ordering.application.OrderSummary;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * A deliberate counterexample, kept because a test proves what it loses.
 *
 * <p>It pages the way almost every list endpoint pages — page number and size, {@code LIMIT} and
 * {@code OFFSET} — and differs from {@link MyBatisOrderQueries} in exactly one respect, so the
 * comparison isolates one variable. Same filter, same ordering, same rows in the table.
 *
 * <p>What it loses is that {@code OFFSET n} means "the n rows before this one, <em>as of now</em>".
 * Between two requests the set shifts: a row that left the filtered set pulls every later row up by
 * one, so the row that moved into position {@code n} is never returned to anybody. Nothing errors,
 * nothing logs, and the client's list is simply missing an order. {@code
 * QueryContractTest#aRowLeavingTheSetMakesTheOffsetPagerSkipAnother} watches it happen.
 *
 * <p>The second cost is not correctness but arithmetic: {@code OFFSET 100000} asks the database to
 * produce and discard a hundred thousand rows. Deep pages get slower the deeper they go, while a
 * seek predicate costs the same on page 1 and page 10,000.
 */
@Component
public class OffsetPager {

  private final OrderMapper mapper;

  OffsetPager(OrderMapper mapper) {
    this.mapper = mapper;
  }

  /** @param pageIndex 1-based, the way an offset API always ends up counting */
  public List<OrderSummary> fetchPage(
      OrderFilter filter, OrderSort sort, int size, long pageIndex) {
    LambdaQueryWrapper<OrderRow> wrapper =
        new LambdaQueryWrapper<OrderRow>()
            .eq(filter.customerId() != null, OrderRow::getCustomerId, filter.customerId())
            .eq(
                filter.status() != null,
                OrderRow::getStatus,
                filter.status() == null ? null : filter.status().name());
    if (sort.descending()) {
      wrapper.orderByDesc(OrderRow::getPlacedAt).orderByDesc(OrderRow::getId);
    } else {
      wrapper.orderByAsc(OrderRow::getPlacedAt).orderByAsc(OrderRow::getId);
    }
    com.baomidou.mybatisplus.extension.plugins.pagination.Page<OrderRow> window =
        new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(pageIndex, size, false);
    return mapper.selectPage(window, wrapper).getRecords().stream()
        .map(MyBatisOrderQueries::toSummary)
        .toList();
  }
}
