package com.example.samples.s04.ordering.infrastructure;

import com.aipersimmon.ddd.application.DomainEvents;
import com.aipersimmon.ddd.persistence.mybatisplus.MybatisPlusAggregateRepository;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.samples.s04.ordering.domain.Order;
import com.example.samples.s04.ordering.domain.OrderId;
import com.example.samples.s04.ordering.domain.OrderLine;
import com.example.samples.s04.ordering.domain.Orders;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * The write path. Nothing here knows about the outbox — the outbox row is written by the {@code
 * IntegrationEvents} implementation, in the same transaction this save runs in, which is why the two
 * commit together without either side coordinating with the other.
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

  /**
   * Note what is <strong>not</strong> here: {@code tenant_id}. Neither statement mentions it, and
   * neither does {@link #toRow}. The tenant-line interceptor rewrites both — adding the column and
   * value to the insert, and {@code AND tenant_id = ?} to the select — from the ambient
   * {@code TenantContext}.
   *
   * <p>That is the difference between SQL-level rewriting and a hand-written predicate: there is no
   * per-query discipline to remember, so there is no query that can forget. The price is that the
   * rewriting happens only for tables named in {@code tenancy.mybatis-plus.tenant-tables}, and an
   * unnamed table silently gets no predicate at all — which is what the library's startup guard
   * exists to catch, and what a test in this module exercises directly.
   */
  @Override
  public Optional<Order> find(OrderId id) {
    OrderRow row = orderMapper.selectById(id.value());
    if (row == null) {
      return Optional.empty();
    }
    List<OrderLine> lines =
        lineMapper
            .selectList(new LambdaQueryWrapper<OrderLineRow>().eq(OrderLineRow::getOrderId, id.value()))
            .stream()
            .map(line -> new OrderLine(line.getSku(), line.getQuantity()))
            .toList();
    return Optional.of(Order.reconstitute(id, row.getCustomerId(), lines, row.getVersion()));
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
