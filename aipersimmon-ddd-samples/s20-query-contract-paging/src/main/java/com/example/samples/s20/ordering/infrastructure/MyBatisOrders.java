package com.example.samples.s20.ordering.infrastructure;

import com.aipersimmon.ddd.application.DomainEvents;
import com.aipersimmon.ddd.persistence.mybatisplus.MybatisPlusAggregateRepository;
import com.example.samples.s20.ordering.domain.Order;
import com.example.samples.s20.ordering.domain.OrderId;
import com.example.samples.s20.ordering.domain.OrderStatus;
import com.example.samples.s20.ordering.domain.Orders;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** The write path. Inherited wholesale; the interesting code in this sample is next door. */
@Repository
class MyBatisOrders extends MybatisPlusAggregateRepository<Order, OrderRow> implements Orders {

  private final OrderMapper mapper;

  MyBatisOrders(OrderMapper mapper, DomainEvents domainEvents) {
    super(mapper, domainEvents);
    this.mapper = mapper;
  }

  @Override
  public void save(Order order) {
    saveAggregate(order);
  }

  @Override
  public Optional<Order> findById(OrderId id) {
    OrderRow row = mapper.selectById(id.value());
    if (row == null) {
      return Optional.empty();
    }
    return Optional.of(
        Order.reconstitute(
            new OrderId(row.getId()),
            row.getCustomerId(),
            row.getQuantity(),
            row.getPlacedAt(),
            OrderStatus.valueOf(row.getStatus()),
            row.getVersion()));
  }

  @Override
  protected OrderRow toRow(Order order) {
    OrderRow row = new OrderRow();
    row.setId(order.id().value());
    row.setCustomerId(order.customerId());
    row.setStatus(order.status().name());
    row.setQuantity(order.quantity());
    row.setPlacedAt(order.placedAt());
    return row;
  }
}
