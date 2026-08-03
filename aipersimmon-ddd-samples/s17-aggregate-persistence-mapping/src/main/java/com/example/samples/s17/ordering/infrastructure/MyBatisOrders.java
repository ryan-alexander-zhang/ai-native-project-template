package com.example.samples.s17.ordering.infrastructure;

import com.aipersimmon.ddd.application.DomainEvents;
import com.aipersimmon.ddd.persistence.mybatisplus.MybatisPlusAggregateRepository;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.samples.s17.ordering.domain.LineId;
import com.example.samples.s17.ordering.domain.Money;
import com.example.samples.s17.ordering.domain.Order;
import com.example.samples.s17.ordering.domain.OrderId;
import com.example.samples.s17.ordering.domain.OrderLine;
import com.example.samples.s17.ordering.domain.OrderStatus;
import com.example.samples.s17.ordering.domain.Orders;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * The mapping, in one class.
 *
 * <p>Everything the base class does for you is the write path: choosing insert or update from the
 * version, applying the {@code WHERE version = ?} predicate, writing back the columns the aggregate
 * emptied, then advancing the version and publishing the events. Everything on the way in — the
 * reading, the assembling, the child strategy — is here, because only the write path carries the
 * invariants.
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
            row.getShippingAddress(),
            OrderStatus.valueOf(row.getStatus()),
            row.getNote(),
            linesOf(row.getId()),
            // Forget this argument and the next save inserts instead of updating.
            row.getVersion()));
  }

  @Override
  protected OrderRow toRow(Order order) {
    OrderRow row = new OrderRow();
    row.setId(order.id().value());
    row.setCustomerId(order.customerId());
    row.setStatus(order.status().name());
    // Mapped whether or not it is set: toRow describes the whole root, so a null here means the
    // aggregate cleared it, and the base class turns that into an explicit `note = null`.
    row.setNote(order.note());
    row.setShippingAddress(order.shippingAddress());
    Money total = order.total();
    row.setTotalCurrency(total.currency());
    row.setTotalAmountCents(total.amountCents());
    return row;
  }

  /**
   * Diffed against what is stored, rather than deleted and reinserted.
   *
   * <p>The strategy follows from the model: these lines are entities, so their identities are part of
   * the domain and must survive a write. Delete-and-reinsert is simpler and perfectly correct for a
   * collection of <em>value objects</em> (S1 does exactly that), but here it would mint new identities
   * on every save, break any foreign key pointing at a line, and rewrite rows nothing touched.
   */
  @Override
  protected void saveChildren(Order order) {
    Map<String, OrderLineRow> stored = new HashMap<>();
    for (OrderLineRow row : linesRowsOf(order.id().value())) {
      stored.put(row.getId(), row);
    }
    for (OrderLine line : order.lines()) {
      OrderLineRow existing = stored.remove(line.id().value());
      if (existing == null) {
        lineMapper.insert(toRow(order.id(), line));
      } else if (!existing.getQuantity().equals(line.quantity())) {
        // Only what actually changed. A line whose quantity is unchanged is not written at all.
        existing.setQuantity(line.quantity());
        lineMapper.updateById(existing);
      }
    }
    // Whatever is still in the map is a line the aggregate no longer has.
    for (OrderLineRow removed : stored.values()) {
      lineMapper.deleteById(removed.getId());
    }
  }

  private static OrderLineRow toRow(OrderId orderId, OrderLine line) {
    OrderLineRow row = new OrderLineRow();
    row.setId(line.id().value());
    row.setOrderId(orderId.value());
    row.setSku(line.sku());
    row.setUnitPriceCurrency(line.unitPrice().currency());
    row.setUnitPriceAmountCents(line.unitPrice().amountCents());
    row.setQuantity(line.quantity());
    return row;
  }

  private List<OrderLineRow> linesRowsOf(String orderId) {
    return lineMapper.selectList(
        new LambdaQueryWrapper<OrderLineRow>()
            .eq(OrderLineRow::getOrderId, orderId)
            .orderByAsc(OrderLineRow::getId));
  }

  private List<OrderLine> linesOf(String orderId) {
    List<OrderLine> lines = new ArrayList<>();
    for (OrderLineRow row : linesRowsOf(orderId)) {
      lines.add(
          OrderLine.restore(
              new LineId(row.getId()),
              row.getSku(),
              Money.of(row.getUnitPriceCurrency(), row.getUnitPriceAmountCents()),
              row.getQuantity()));
    }
    return lines;
  }
}
