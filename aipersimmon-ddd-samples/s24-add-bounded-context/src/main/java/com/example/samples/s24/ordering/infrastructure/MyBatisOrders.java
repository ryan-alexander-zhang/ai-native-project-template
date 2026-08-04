package com.example.samples.s24.ordering.infrastructure;

import com.aipersimmon.ddd.application.DomainEvents;
import com.aipersimmon.ddd.persistence.mybatisplus.MybatisPlusAggregateRepository;
import com.example.samples.s24.ordering.domain.Order;
import com.example.samples.s24.ordering.domain.OrderId;
import com.example.samples.s24.ordering.domain.OrderLine;
import com.example.samples.s24.ordering.domain.OrderStatus;
import com.example.samples.s24.ordering.domain.Orders;
import com.example.samples.s24.sharedkernel.api.Money;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** The order's write path, root row plus lines. */
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
  public void save(Order order) {
    saveAggregate(order);
  }

  @Override
  public Optional<Order> find(OrderId id) {
    OrderRow row = orders.selectById(id.value());
    if (row == null) {
      return Optional.empty();
    }
    List<OrderLine> orderLines =
        lines.findByOrder(id.value()).stream()
            .map(
                line ->
                    new OrderLine(
                        line.getLineNo(),
                        line.getSku(),
                        line.getQuantity(),
                        Money.of(line.getUnitMinor(), row.getCurrency())))
            .toList();
    return Optional.of(
        Order.reconstitute(
            id,
            row.getCustomerId(),
            orderLines,
            Money.of(row.getGrossMinor(), row.getCurrency()),
            Money.of(row.getDiscountMinor(), row.getCurrency()),
            row.getCouponCode(),
            OrderStatus.valueOf(row.getStatus()),
            row.getPlacedAt(),
            row.getVersion()));
  }

  @Override
  protected OrderRow toRow(Order order) {
    OrderRow row = new OrderRow();
    row.setId(order.id().value());
    row.setCustomerId(order.customerId());
    row.setStatus(order.status().name());
    row.setGrossMinor(order.gross().minor());
    row.setDiscountMinor(order.discount().minor());
    row.setCurrency(order.gross().currency());
    row.setCouponCode(order.couponCode().orElse(null));
    row.setPlacedAt(order.placedAt());
    return row;
  }

  /** Rewritten wholesale, which is right for a handful of lines and is S17's subject rather than this one's. */
  @Override
  protected void saveChildren(Order order) {
    lines.deleteByOrder(order.id().value());
    for (OrderLine line : order.lines()) {
      OrderLineRow row = new OrderLineRow();
      row.setOrderId(order.id().value());
      row.setLineNo(line.lineNo());
      row.setSku(line.sku());
      row.setQuantity(line.quantity());
      row.setUnitMinor(line.unitPrice().minor());
      lines.insert(row);
    }
  }
}
