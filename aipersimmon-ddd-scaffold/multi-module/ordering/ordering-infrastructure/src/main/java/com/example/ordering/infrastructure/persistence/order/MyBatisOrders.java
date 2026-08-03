package com.example.ordering.infrastructure.persistence.order;

import com.aipersimmon.ddd.application.DomainEvents;
import com.aipersimmon.ddd.persistence.mybatisplus.MybatisPlusAggregateRepository;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.ordering.domain.customer.CustomerId;
import com.example.ordering.domain.order.LineData;
import com.example.ordering.domain.order.Order;
import com.example.ordering.domain.order.OrderId;
import com.example.ordering.domain.order.OrderStatus;
import com.example.ordering.domain.order.Orders;
import com.example.ordering.domain.shared.Money;
import com.example.ordering.domain.shared.Sku;
import java.time.Clock;
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
public class MyBatisOrders extends MybatisPlusAggregateRepository<Order, OrderDo>
    implements Orders {

  private final OrderMapper orders;
  private final OrderLineMapper lines;

  /** The application's single time source; stamps {@code created_at} on the first save. */
  private final Clock clock;

  public MyBatisOrders(
      OrderMapper orders, OrderLineMapper lines, DomainEvents domainEvents, Clock clock) {
    super(orders, domainEvents);
    this.orders = orders;
    this.lines = lines;
    this.clock = clock;
  }

  @Override
  public void save(Order order) {
    saveAggregate(order);
  }

  @Override
  protected OrderDo toRow(Order order) {
    OrderDo header = new OrderDo();
    header.setId(order.id().value());
    header.setCustomerId(order.customerId().value());
    header.setStatus(order.status().name());
    // Frozen here rather than re-derived by the read model, so "total = Σ line subtotals" has one
    // definition and the currency rule (Money.plus refuses to mix) travels with it.
    header.setTotalMinor(order.total().amountMinor());
    header.setCurrency(order.total().currency());
    // Stamped on every toRow but written only by the INSERT: the column's FieldStrategy.NEVER
    // keeps updates from touching it, so the first save's instant is the one that persists.
    header.setCreatedAt(clock.instant());
    return header;
  }

  /**
   * Writes the line set, and only when the aggregate says it changed.
   *
   * <p>There is no change tracking here on purpose — the aggregate is asked instead. It used to
   * rewrite unconditionally, described as safe because "an order's line set is small and only set
   * at placement". Both halves of that were true and the conclusion did not follow: precisely
   * because lines are only set at placement, every <em>other</em> save — a confirm, a cancel, a
   * begin-fulfilment, each of which touches only {@code status} — deleted and re-inserted the whole
   * set to arrive at the rows already there. Pure cost, scaling with the line count, on every
   * lifecycle transition.
   *
   * <p>Its bound is worth stating because it is what keeps the shortcut honest: this is only
   * correct while nothing can mutate lines without saying so, which is exactly what {@code
   * Order.lineSetChanged()} makes explicit rather than assumed. The delete is kept for when the
   * flag is set, so a future line-editing use case gets replace semantics with no change here.
   */
  @Override
  protected void saveChildren(Order order) {
    if (!order.lineSetChanged()) {
      return;
    }
    String id = order.id().value();
    lines.delete(new LambdaQueryWrapper<OrderLineDo>().eq(OrderLineDo::getOrderId, id));
    List<LineData> lineData = order.lineData();
    List<OrderLineDo> rows = new ArrayList<>(lineData.size());
    for (int i = 0; i < lineData.size(); i++) {
      LineData line = lineData.get(i);
      OrderLineDo row = new OrderLineDo();
      row.setOrderId(id);
      row.setLineNo(i);
      row.setSku(line.sku().value());
      row.setQuantity(line.quantity());
      row.setUnitMinor(line.unitPrice().amountMinor());
      row.setCurrency(line.unitPrice().currency());
      rows.add(row);
    }
    // One statement rather than one per line: the placement path is the only one that writes lines,
    // so this is where a multi-line order's round trips actually are.
    lines.insert(rows);
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
              new Sku(row.getSku()),
              row.getQuantity(),
              Money.of(row.getUnitMinor(), row.getCurrency())));
    }
    return Optional.of(
        Order.reconstitute(
            id,
            new CustomerId(header.getCustomerId()),
            lineData,
            OrderStatus.valueOf(header.getStatus()),
            header.getVersion()));
  }
}
