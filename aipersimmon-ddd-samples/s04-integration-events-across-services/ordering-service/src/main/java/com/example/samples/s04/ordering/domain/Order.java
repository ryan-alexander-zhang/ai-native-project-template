package com.example.samples.s04.ordering.domain;

import com.aipersimmon.ddd.core.annotation.AggregateRoot;
import com.aipersimmon.ddd.core.model.AbstractAggregateRoot;
import java.util.List;

/** A placed order. Small on purpose: this sample is about what leaves the service. */
@AggregateRoot
public final class Order extends AbstractAggregateRoot<OrderId> {

  private final OrderId id;
  private final String customerId;
  private final List<OrderLine> lines;

  private Order(OrderId id, String customerId, List<OrderLine> lines) {
    this.id = id;
    this.customerId = customerId;
    this.lines = List.copyOf(lines);
  }

  public static Order place(OrderId id, String customerId, List<OrderLine> lines) {
    Order order = new Order(id, customerId, lines);
    order.checkInvariant(new OrderHasLines(order.lines));
    return order;
  }

  public static Order reconstitute(
      OrderId id, String customerId, List<OrderLine> lines, long version) {
    Order order = new Order(id, customerId, lines);
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
}
