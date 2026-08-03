package com.example.samples.s21.ordering.infrastructure;

import com.aipersimmon.ddd.application.DomainEvents;
import com.aipersimmon.ddd.persistence.mybatisplus.MybatisPlusAggregateRepository;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.samples.s21.ordering.domain.Order;
import com.example.samples.s21.ordering.domain.OrderLine;
import com.example.samples.s21.ordering.domain.Orders;
import java.util.List;
import org.springframework.stereotype.Repository;

/** The write path. Mapping detail is S17; the outbox row is the framework's business, not this one's. */
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
    row.setWarehouseCode(order.warehouseCode());
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
