package com.example.samples.s06.ordering.infrastructure;

import com.aipersimmon.ddd.application.DomainEvents;
import com.aipersimmon.ddd.persistence.mybatisplus.MybatisPlusAggregateRepository;
import com.example.samples.s06.ordering.domain.Order;
import com.example.samples.s06.ordering.domain.Orders;
import org.springframework.stereotype.Repository;

/** The write path. */
@Repository
class MyBatisOrders extends MybatisPlusAggregateRepository<Order, OrderRow> implements Orders {

  MyBatisOrders(OrderMapper mapper, DomainEvents domainEvents) {
    super(mapper, domainEvents);
  }

  @Override
  public void save(Order order) {
    saveAggregate(order);
  }

  @Override
  protected OrderRow toRow(Order order) {
    OrderRow row = new OrderRow();
    row.setId(order.id().value());
    row.setCustomerId(order.customerId());
    row.setAmountCents(order.amountCents());
    return row;
  }
}
