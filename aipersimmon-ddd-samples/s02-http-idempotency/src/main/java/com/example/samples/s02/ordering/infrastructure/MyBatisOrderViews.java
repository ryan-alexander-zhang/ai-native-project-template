package com.example.samples.s02.ordering.infrastructure;

import com.example.samples.s02.ordering.application.OrderView;
import com.example.samples.s02.ordering.application.OrderViews;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** The read side: rows straight to the view. */
@Repository
class MyBatisOrderViews implements OrderViews {

  private final OrderMapper mapper;

  MyBatisOrderViews(OrderMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public Optional<OrderView> findById(String orderId) {
    OrderRow row = mapper.selectById(orderId);
    if (row == null) {
      return Optional.empty();
    }
    return Optional.of(
        new OrderView(row.getId(), row.getClientReference(), row.getAmountCents()));
  }
}
