package com.example.samples.s12.ordering.infrastructure;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.samples.s12.ordering.application.OrderFacts;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** Reading the write model flat, for the projection's benefit. */
@Repository
class MyBatisOrderFacts implements OrderFacts {

  private final OrderMapper orders;
  private final OrderLineMapper lines;

  MyBatisOrderFacts(OrderMapper orders, OrderLineMapper lines) {
    this.orders = orders;
    this.lines = lines;
  }

  @Override
  public Optional<OrderFact> find(String orderId) {
    OrderRow row = orders.selectById(orderId);
    if (row == null) {
      return Optional.empty();
    }
    List<String> skus =
        lines
            .selectList(new LambdaQueryWrapper<OrderLineRow>().eq(OrderLineRow::getOrderId, orderId))
            .stream()
            .map(OrderLineRow::getSku)
            .toList();
    return Optional.of(
        new OrderFact(
            row.getId(),
            row.getCustomerId(),
            row.getStatus(),
            row.getPlacedAt(),
            row.getPaidAt(),
            row.getTotalMinor(),
            skus));
  }

  /**
   * Every order that ever contained this sku.
   *
   * <p>The index {@code s12_order_line_sku} exists for this one query, and the query is the whole cost of a
   * rename: unbounded in the number of orders, and growing for as long as the product sells. The count is
   * returned to the caller rather than logged and forgotten, so the amplification is visible.
   */
  @Override
  public List<String> orderIdsContaining(String sku) {
    return lines
        .selectList(new LambdaQueryWrapper<OrderLineRow>().eq(OrderLineRow::getSku, sku))
        .stream()
        .map(OrderLineRow::getOrderId)
        .distinct()
        .toList();
  }

  @Override
  public List<String> allOrderIds() {
    return orders
        .selectList(new LambdaQueryWrapper<OrderRow>().orderByAsc(OrderRow::getPlacedAt))
        .stream()
        .map(OrderRow::getId)
        .toList();
  }
}
