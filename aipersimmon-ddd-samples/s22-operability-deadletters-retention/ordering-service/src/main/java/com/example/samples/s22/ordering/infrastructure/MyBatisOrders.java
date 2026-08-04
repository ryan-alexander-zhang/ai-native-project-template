package com.example.samples.s22.ordering.infrastructure;

import com.aipersimmon.ddd.application.DomainEvents;
import com.aipersimmon.ddd.persistence.mybatisplus.MybatisPlusAggregateRepository;
import com.example.samples.s22.ordering.domain.Order;
import com.example.samples.s22.ordering.domain.OrderId;
import com.example.samples.s22.ordering.domain.Orders;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** The write path. */
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
  public Optional<Order> find(OrderId id) {
    OrderRow row = mapper.selectById(id.value());
    return row == null
        ? Optional.empty()
        : Optional.of(
            Order.reconstitute(
                id, row.getCustomerId(), row.getSku(), row.getQuantity(), row.getVersion()));
  }

  @Override
  protected OrderRow toRow(Order order) {
    OrderRow row = new OrderRow();
    row.setId(order.id().value());
    row.setCustomerId(order.customerId());
    row.setSku(order.sku());
    row.setQuantity(order.quantity());
    return row;
  }
}
