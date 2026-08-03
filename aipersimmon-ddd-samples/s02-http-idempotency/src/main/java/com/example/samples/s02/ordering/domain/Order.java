package com.example.samples.s02.ordering.domain;

import com.aipersimmon.ddd.core.annotation.AggregateRoot;
import com.aipersimmon.ddd.core.model.AbstractAggregateRoot;

/**
 * Deliberately thin: this sample is about what happens at the edge before a command is dispatched, so
 * the order has no lifecycle of its own. Modelling is S16.
 */
@AggregateRoot
public final class Order extends AbstractAggregateRoot<OrderId> {

  private final OrderId id;
  private final ClientReference clientReference;
  private final long amountCents;

  private Order(OrderId id, ClientReference clientReference, long amountCents) {
    this.id = id;
    this.clientReference = clientReference;
    this.amountCents = amountCents;
  }

  public static Order place(OrderId id, ClientReference clientReference, long amountCents) {
    if (amountCents <= 0) {
      throw new IllegalArgumentException("amount must be positive: " + amountCents);
    }
    Order order = new Order(id, clientReference, amountCents);
    order.registerEvent(new OrderPlaced(id, clientReference, amountCents));
    return order;
  }

  public static Order reconstitute(
      OrderId id, ClientReference clientReference, long amountCents, long version) {
    Order order = new Order(id, clientReference, amountCents);
    order.restoreVersion(version);
    return order;
  }

  @Override
  public OrderId id() {
    return id;
  }

  public ClientReference clientReference() {
    return clientReference;
  }

  public long amountCents() {
    return amountCents;
  }
}
