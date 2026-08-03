package com.example.samples.s18.ordering.infrastructure;

import com.example.samples.s18.ordering.application.OrderView;
import com.example.samples.s18.ordering.application.OrderViews;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** The read side. */
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
        new OrderView(row.getId(), row.getCustomerId(), row.getAmountCents(), row.getStatus()));
  }
}
