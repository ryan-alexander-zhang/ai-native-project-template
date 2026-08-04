package com.example.samples.s22.ordering.domain;

import com.aipersimmon.ddd.core.annotation.AggregateRoot;
import com.aipersimmon.ddd.core.model.AbstractAggregateRoot;

/**
 * A placed order. One sku, one quantity — the aggregate is not the subject here.
 *
 * <p>What matters operationally is what this class does <em>not</em> have: no {@code published}
 * flag, no {@code attempts}, no {@code lastError}. Delivery bookkeeping on the aggregate is the
 * mistake that makes an incident unfixable, because replaying then means writing to the write model,
 * which means the version check, the invariants and (worse) new events. The outbox keeps that
 * bookkeeping in its own row so an operator can act on it without touching business state.
 */
@AggregateRoot
public final class Order extends AbstractAggregateRoot<OrderId> {

  private final OrderId id;
  private final String customerId;
  private final String sku;
  private final int quantity;

  private Order(OrderId id, String customerId, String sku, int quantity) {
    this.id = id;
    this.customerId = customerId;
    this.sku = sku;
    this.quantity = quantity;
  }

  public static Order place(OrderId id, String customerId, String sku, int quantity) {
    Order order = new Order(id, customerId, sku, quantity);
    order.checkInvariant(new OrderIsForSomething(sku, quantity));
    return order;
  }

  public static Order reconstitute(
      OrderId id, String customerId, String sku, int quantity, long version) {
    Order order = new Order(id, customerId, sku, quantity);
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

  public String sku() {
    return sku;
  }

  public int quantity() {
    return quantity;
  }
}
