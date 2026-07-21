package com.example.ordering.infrastructure.persistence.order;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.ordering.domain.customer.CustomerId;
import com.example.ordering.domain.order.LineData;
import com.example.ordering.domain.order.Order;
import com.example.ordering.domain.order.OrderId;
import com.example.ordering.domain.order.OrderStatus;
import com.example.ordering.domain.order.Orders;
import com.example.ordering.domain.shared.Money;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * PostgreSQL-backed {@link Orders}: the order header in {@code ordering.orders} and its lines in
 * {@code ordering.order_lines}. Runs inside the command's transaction (the CQRS {@code
 * TransactionCommandInterceptor}) on the same DataSource as the outbox, so the aggregate and its
 * integration event commit atomically. save() rewrites the lines wholesale (delete + insert) — an
 * order's line set is small and only set at placement.
 */
@Repository
public class MyBatisOrders implements Orders {

  private final OrderMapper orders;
  private final OrderLineMapper lines;

  public MyBatisOrders(OrderMapper orders, OrderLineMapper lines) {
    this.orders = orders;
    this.lines = lines;
  }

  @Override
  public void save(Order order) {
    String id = order.id().value();
    OrderDo header = new OrderDo();
    header.setId(id);
    header.setCustomerId(order.customerId().value());
    header.setStatus(order.status().name());
    if (orders.selectById(id) == null) {
      orders.insert(header);
    } else {
      orders.updateById(header);
    }

    lines.delete(new LambdaQueryWrapper<OrderLineDo>().eq(OrderLineDo::getOrderId, id));
    List<LineData> lineData = order.lineData();
    for (int i = 0; i < lineData.size(); i++) {
      LineData line = lineData.get(i);
      OrderLineDo row = new OrderLineDo();
      row.setOrderId(id);
      row.setLineNo(i);
      row.setSku(line.sku());
      row.setQuantity(line.quantity());
      row.setUnitMinor(line.unitPrice().amountMinor());
      row.setCurrency(line.unitPrice().currency());
      lines.insert(row);
    }
  }

  @Override
  public Optional<Order> findById(OrderId id) {
    OrderDo header = orders.selectById(id.value());
    if (header == null) {
      return Optional.empty();
    }
    List<OrderLineDo> rows =
        lines.selectList(
            new LambdaQueryWrapper<OrderLineDo>()
                .eq(OrderLineDo::getOrderId, id.value())
                .orderByAsc(OrderLineDo::getLineNo));
    List<LineData> lineData = new ArrayList<>();
    for (OrderLineDo row : rows) {
      lineData.add(
          new LineData(
              row.getSku(), row.getQuantity(), Money.of(row.getUnitMinor(), row.getCurrency())));
    }
    return Optional.of(
        Order.reconstitute(
            id,
            new CustomerId(header.getCustomerId()),
            lineData,
            OrderStatus.valueOf(header.getStatus())));
  }
}
