package com.example.samples.s19.ordering.domain;

import com.aipersimmon.ddd.core.annotation.AggregateRoot;
import com.aipersimmon.ddd.core.model.AbstractAggregateRoot;

/** The aggregate owns the rule that must hold of the state it is about to write. */
@AggregateRoot
public final class Order extends AbstractAggregateRoot<OrderId> {

  public static final int PER_ORDER_CAP = 100;

  private final OrderId id;
  private final String customerId;
  private final int quantity;

  private Order(OrderId id, String customerId, int quantity) {
    this.id = id;
    this.customerId = customerId;
    this.quantity = quantity;
  }

  public static Order place(OrderId id, String customerId, int quantity) {
    Order order = new Order(id, customerId, quantity);
    order.checkInvariant(new QuantityWithinCap(quantity, PER_ORDER_CAP));
    return order;
  }

  public static Order reconstitute(OrderId id, String customerId, int quantity, long version) {
    Order order = new Order(id, customerId, quantity);
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

  public int quantity() {
    return quantity;
  }
}
