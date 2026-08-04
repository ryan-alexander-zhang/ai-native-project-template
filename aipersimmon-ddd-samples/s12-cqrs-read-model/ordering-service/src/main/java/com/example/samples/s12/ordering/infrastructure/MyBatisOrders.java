package com.example.samples.s12.ordering.infrastructure;

import com.aipersimmon.ddd.application.DomainEvents;
import com.aipersimmon.ddd.persistence.mybatisplus.MybatisPlusAggregateRepository;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.samples.s12.ordering.domain.Order;
import com.example.samples.s12.ordering.domain.OrderId;
import com.example.samples.s12.ordering.domain.OrderStatus;
import com.example.samples.s12.ordering.domain.Orders;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * The order adapter — and the reason the projection is transactionally consistent with the write.
 *
 * <p>{@code saveAggregate} drains the aggregate's domain events and publishes them inside this save's
 * transaction. The projection's {@code @EventListener} therefore runs before this transaction commits, so the
 * order row and its list row are one atomic outcome. Nothing in the handler arranges that; it falls out of
 * where the publishing happens.
 */
@Repository
class MyBatisOrders extends MybatisPlusAggregateRepository<Order, OrderRow> implements Orders {

  private final OrderMapper orders;
  private final OrderLineMapper lines;

  MyBatisOrders(OrderMapper orders, OrderLineMapper lines, DomainEvents domainEvents) {
    super(orders, domainEvents);
    this.orders = orders;
    this.lines = lines;
  }

  @Override
  public Optional<Order> find(OrderId id) {
    OrderRow row = orders.selectById(id.value());
    if (row == null) {
      return Optional.empty();
    }
    List<Order.Line> orderLines =
        lines
            .selectList(new LambdaQueryWrapper<OrderLineRow>().eq(OrderLineRow::getOrderId, id.value()))
            .stream()
            .map(
                line ->
                    new Order.Line(
                        line.getSku(),
                        line.getQuantity(),
                        line.getUnitPriceMinor(),
                        line.getNameAtPurchase()))
            .toList();
    return Optional.of(
        Order.reconstitute(
            id,
            row.getCustomerId(),
            orderLines,
            row.getPlacedAt(),
            OrderStatus.valueOf(row.getStatus()),
            row.getPaidAt(),
            row.getVersion()));
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
    row.setStatus(order.status().name());
    row.setPlacedAt(order.placedAt());
    row.setPaidAt(order.paidAt());
    row.setTotalMinor(order.totalMinor());
    return row;
  }

  /**
   * Lines never change after placement here, so they are written once and left alone.
   *
   * <p>The guard is {@code version() > 0}, not {@code > 1}. {@code saveChildren} runs <em>before</em> the base
   * class advances the version, so at this point a brand-new aggregate is still at 0 and an existing one is at
   * whatever it was loaded with. Getting that off by one rewrote the lines on every subsequent save and the
   * insert failed on the primary key — which is at least a loud failure, and only because the line ids are
   * derived from the order id rather than generated fresh each time.
   */
  @Override
  protected void saveChildren(Order order) {
    if (order.version() > 0) {
      return;
    }
    int index = 0;
    for (Order.Line line : order.lines()) {
      OrderLineRow row = new OrderLineRow();
      row.setId(order.id().value() + "#" + index++);
      row.setOrderId(order.id().value());
      row.setSku(line.sku());
      row.setQuantity(line.quantity());
      row.setUnitPriceMinor(line.unitPriceMinor());
      row.setNameAtPurchase(line.nameAtPurchase());
      lines.insert(row);
    }
  }
}
