package com.example.samples.s04.ordering.infrastructure;

import com.aipersimmon.ddd.application.DomainEvents;
import com.aipersimmon.ddd.persistence.mybatisplus.MybatisPlusAggregateRepository;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.samples.s04.ordering.domain.Order;
import com.example.samples.s04.ordering.domain.OrderLine;
import com.example.samples.s04.ordering.domain.Orders;
import java.util.List;
import org.springframework.stereotype.Repository;

/**
 * The write path. Nothing here knows about the outbox — the outbox row is written by the {@code
 * IntegrationEvents} implementation, in the same transaction this save runs in, which is why the two
 * commit together without either side coordinating with the other.
 */
@Repository
class MyBatisOrders extends MybatisPlusAggregateRepository<Order, OrderRow> implements Orders {

  private final OrderLineMapper lineMapper;

  MyBatisOrders(OrderMapper orderMapper, OrderLineMapper lineMapper, DomainEvents domainEvents) {
    super(orderMapper, domainEvents);
    this.lineMapper = lineMapper;
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
    return row;
  }

  @Override
  protected void saveChildren(Order order) {
    lineMapper.delete(
        new LambdaQueryWrapper<OrderLineRow>().eq(OrderLineRow::getOrderId, order.id().value()));
    List<OrderLine> lines = order.lines();
    for (int index = 0; index < lines.size(); index++) {
      OrderLineRow row = new OrderLineRow();
      row.setId(order.id().value() + ":" + index);
      row.setOrderId(order.id().value());
      row.setSku(lines.get(index).sku());
      row.setQuantity(lines.get(index).quantity());
      lineMapper.insert(row);
    }
  }
}
