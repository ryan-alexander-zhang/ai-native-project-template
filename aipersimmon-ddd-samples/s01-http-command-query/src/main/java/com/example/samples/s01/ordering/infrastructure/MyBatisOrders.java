package com.example.samples.s01.ordering.infrastructure;

import com.aipersimmon.ddd.application.DomainEvents;
import com.aipersimmon.ddd.persistence.mybatisplus.MybatisPlusAggregateRepository;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.samples.s01.ordering.domain.Order;
import com.example.samples.s01.ordering.domain.OrderId;
import com.example.samples.s01.ordering.domain.OrderLine;
import com.example.samples.s01.ordering.domain.OrderStatus;
import com.example.samples.s01.ordering.domain.Orders;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * The order repository.
 *
 * <p>Only the write path is inherited — {@code saveAggregate} does the version-checked write, the
 * child rows and the domain-event publication, in that order, inside the caller's transaction. Reads
 * are entirely this class's business, because only the write path carries the invariants.
 */
@Repository
class MyBatisOrders extends MybatisPlusAggregateRepository<Order, OrderRow> implements Orders {

  private final OrderMapper orderMapper;
  private final OrderLineMapper lineMapper;

  MyBatisOrders(OrderMapper orderMapper, OrderLineMapper lineMapper, DomainEvents domainEvents) {
    super(orderMapper, domainEvents);
    this.orderMapper = orderMapper;
    this.lineMapper = lineMapper;
  }

  @Override
  public void save(Order order) {
    saveAggregate(order);
  }

  @Override
  public Optional<Order> findById(OrderId id) {
    OrderRow row = orderMapper.selectById(id.value());
    if (row == null) {
      return Optional.empty();
    }
    return Optional.of(
        Order.reconstitute(
            new OrderId(row.getId()),
            row.getCustomerId(),
            linesOf(row.getId()),
            OrderStatus.valueOf(row.getStatus()),
            // Carrying the version back is what keeps the next save an update rather than an insert.
            row.getVersion()));
  }

  @Override
  protected OrderRow toRow(Order order) {
    OrderRow row = new OrderRow();
    row.setId(order.id().value());
    row.setCustomerId(order.customerId());
    row.setStatus(order.status().name());
    return row;
  }

  /**
   * Lines are a value-object collection: they have no identity to preserve, so the simplest correct
   * strategy is to replace them wholesale. When a child collection does have identity, or is large
   * enough that churn matters, the choice stops being free — that is S17.
   */
  @Override
  protected void saveChildren(Order order) {
    lineMapper.delete(
        new LambdaQueryWrapper<OrderLineRow>().eq(OrderLineRow::getOrderId, order.id().value()));
    List<OrderLine> lines = order.lines();
    for (int index = 0; index < lines.size(); index++) {
      OrderLine line = lines.get(index);
      OrderLineRow row = new OrderLineRow();
      row.setId(order.id().value() + ":" + index);
      row.setOrderId(order.id().value());
      row.setSku(line.sku());
      row.setQuantity(line.quantity());
      lineMapper.insert(row);
    }
  }

  private List<OrderLine> linesOf(String orderId) {
    return lineMapper
        .selectList(
            new LambdaQueryWrapper<OrderLineRow>()
                .eq(OrderLineRow::getOrderId, orderId)
                .orderByAsc(OrderLineRow::getId))
        .stream()
        .map(row -> new OrderLine(row.getSku(), row.getQuantity()))
        .toList();
  }
}
