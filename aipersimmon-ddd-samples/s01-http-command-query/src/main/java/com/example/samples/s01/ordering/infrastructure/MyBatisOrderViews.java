package com.example.samples.s01.ordering.infrastructure;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.samples.s01.ordering.application.OrderView;
import com.example.samples.s01.ordering.application.OrderViews;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * The read side. It maps rows straight to the view and never loads an {@code Order}: an aggregate
 * exists to protect writes, and rebuilding one to read three fields off it buys nothing.
 */
@Repository
class MyBatisOrderViews implements OrderViews {

  private final OrderMapper orderMapper;
  private final OrderLineMapper lineMapper;

  MyBatisOrderViews(OrderMapper orderMapper, OrderLineMapper lineMapper) {
    this.orderMapper = orderMapper;
    this.lineMapper = lineMapper;
  }

  @Override
  public Optional<OrderView> findById(String orderId) {
    OrderRow row = orderMapper.selectById(orderId);
    if (row == null) {
      return Optional.empty();
    }
    List<OrderView.LineView> lines =
        lineMapper
            .selectList(
                new LambdaQueryWrapper<OrderLineRow>()
                    .eq(OrderLineRow::getOrderId, orderId)
                    .orderByAsc(OrderLineRow::getId))
            .stream()
            .map(line -> new OrderView.LineView(line.getSku(), line.getQuantity()))
            .toList();
    return Optional.of(
        new OrderView(row.getId(), row.getCustomerId(), row.getStatus(), lines));
  }
}
