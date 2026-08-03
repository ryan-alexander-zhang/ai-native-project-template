package com.example.samples.s20.ordering.domain;

import com.aipersimmon.ddd.core.annotation.AggregateRoot;
import com.aipersimmon.ddd.core.model.AbstractAggregateRoot;
import java.time.Instant;

/**
 * The write side, kept to the minimum this sample needs: rows to page through, and one way to make a
 * row leave the list while a client is halfway down it.
 *
 * <p>{@code placedAt} is stored rather than derived from the id. The id is time-ordered (UUIDv7), so
 * it would answer "when" well enough to sort by — but {@code IdGenerator}'s contract says the value
 * is <em>opaque</em>: callers must not parse it or depend on the embedded timestamp being there. An
 * ordering the business cares about therefore needs a column it owns, and the id is what breaks
 * ties in it.
 */
@AggregateRoot
public final class Order extends AbstractAggregateRoot<OrderId> {

  private final OrderId id;
  private final String customerId;
  private final int quantity;
  private final Instant placedAt;
  private OrderStatus status;

  private Order(
      OrderId id, String customerId, int quantity, Instant placedAt, OrderStatus status) {
    this.id = id;
    this.customerId = customerId;
    this.quantity = quantity;
    this.placedAt = placedAt;
    this.status = status;
  }

  public static Order place(OrderId id, String customerId, int quantity, Instant placedAt) {
    Order order = new Order(id, customerId, quantity, placedAt, OrderStatus.PLACED);
    order.checkInvariant(new QuantityIsPositive(quantity));
    return order;
  }

  /** Rebuilds a persisted order; the version has to come back or the next save would insert. */
  public static Order reconstitute(
      OrderId id,
      String customerId,
      int quantity,
      Instant placedAt,
      OrderStatus status,
      long version) {
    Order order = new Order(id, customerId, quantity, placedAt, status);
    order.restoreVersion(version);
    return order;
  }

  /** Moves the order out of the default list's filter — the interleaving the tests need. */
  public void confirm() {
    this.status = OrderStatus.CONFIRMED;
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

  public Instant placedAt() {
    return placedAt;
  }

  public OrderStatus status() {
    return status;
  }
}
