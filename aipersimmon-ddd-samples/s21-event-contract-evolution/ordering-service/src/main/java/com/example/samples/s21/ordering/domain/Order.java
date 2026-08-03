package com.example.samples.s21.ordering.domain;

import com.aipersimmon.ddd.core.annotation.AggregateRoot;
import com.aipersimmon.ddd.core.model.AbstractAggregateRoot;
import java.util.List;

/**
 * A placed order. Small on purpose: S21 is about the contract, not the aggregate.
 *
 * <p>It carries {@code warehouseCode} because the business learned it, not because a contract wanted
 * it. The aggregate does not know that a published revision grew a field — and would look the same if
 * nothing were published at all.
 */
@AggregateRoot
public final class Order extends AbstractAggregateRoot<OrderId> {

  private final OrderId id;
  private final String customerId;
  private final List<OrderLine> lines;
  private final String warehouseCode;

  private Order(OrderId id, String customerId, List<OrderLine> lines, String warehouseCode) {
    this.id = id;
    this.customerId = customerId;
    this.lines = List.copyOf(lines);
    this.warehouseCode = warehouseCode;
  }

  public static Order place(
      OrderId id, String customerId, List<OrderLine> lines, String warehouseCode) {
    Order order = new Order(id, customerId, lines, warehouseCode);
    order.checkInvariant(new OrderHasLines(order.lines));
    return order;
  }

  public static Order reconstitute(
      OrderId id, String customerId, List<OrderLine> lines, String warehouseCode, long version) {
    Order order = new Order(id, customerId, lines, warehouseCode);
    order.restoreVersion(version);
    return order;
  }

  @Override
  public OrderId id() {
    return id;
  }

  public String customerId() {
    return customerId;
  }

  public List<OrderLine> lines() {
    return lines;
  }

  public String warehouseCode() {
    return warehouseCode;
  }
}
