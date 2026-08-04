package com.example.samples.s12.ordering.infrastructure;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.samples.s12.ordering.application.OrderListItem;
import com.example.samples.s12.ordering.application.OrderListQueries;
import com.example.samples.s12.ordering.application.OrderListWriter;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * The projection table, written and read.
 *
 * <p>One class for both ports on purpose: the write shape and the read shape of a projection have to agree
 * exactly, and splitting them across two files is how a column gets added to one and forgotten in the other.
 * That is a different judgement from the write side, where {@code Orders} (aggregates) and {@link
 * MyBatisOrderFacts} (flat) are deliberately separate — there the shapes are meant to differ.
 */
@Repository
class MyBatisOrderList implements OrderListWriter, OrderListQueries {

  private final OrderListMapper mapper;

  MyBatisOrderList(OrderListMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public void save(OrderListItem item) {
    OrderListRow row = toRow(item);
    if (mapper.selectById(item.orderId()) == null) {
      mapper.insert(row);
    } else {
      mapper.updateById(row);
    }
  }

  @Override
  public void deleteAll() {
    mapper.delete(new LambdaQueryWrapper<>());
  }

  @Override
  public List<OrderListItem> recentFor(String customerId, int limit) {
    // Through the pagination interceptor rather than a hand-built LIMIT: the value is bounded and validated,
    // so a string would be safe here, but "the read side never builds SQL by hand" is a rule worth keeping
    // whole. Cursor paging, total ordering and what offset loses are S20's subject.
    Page<OrderListRow> page =
        mapper.selectPage(
            Page.of(1, limit),
            new LambdaQueryWrapper<OrderListRow>()
                .eq(OrderListRow::getCustomerId, customerId)
                .orderByDesc(OrderListRow::getPlacedAt));
    return page.getRecords().stream().map(MyBatisOrderList::toItem).toList();
  }

  @Override
  public Optional<OrderListItem> find(String orderId) {
    return Optional.ofNullable(mapper.selectById(orderId)).map(MyBatisOrderList::toItem);
  }

  private static OrderListRow toRow(OrderListItem item) {
    OrderListRow row = new OrderListRow();
    row.setOrderId(item.orderId());
    row.setCustomerId(item.customerId());
    row.setStatus(item.status());
    row.setPlacedAt(item.placedAt());
    row.setPaidAt(item.paidAt());
    row.setLineCount(item.lineCount());
    row.setTotalMinor(item.totalMinor());
    row.setDisplaySummary(item.displaySummary());
    row.setProjectedAt(item.projectedAt());
    return row;
  }

  private static OrderListItem toItem(OrderListRow row) {
    return new OrderListItem(
        row.getOrderId(),
        row.getCustomerId(),
        row.getStatus(),
        row.getPlacedAt(),
        row.getPaidAt(),
        row.getLineCount(),
        row.getTotalMinor(),
        row.getDisplaySummary(),
        row.getProjectedAt());
  }
}
