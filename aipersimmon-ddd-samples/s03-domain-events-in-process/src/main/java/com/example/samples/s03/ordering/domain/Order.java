package com.example.samples.s03.ordering.domain;

import com.aipersimmon.ddd.core.annotation.AggregateRoot;
import com.aipersimmon.ddd.core.model.AbstractAggregateRoot;

/** Thin: this sample is about what happens to the events the aggregate records. */
@AggregateRoot
public final class Order extends AbstractAggregateRoot<OrderId> {

  private final OrderId id;
  private final String customerId;
  private final long amountCents;
  private String reviewReason;

  private Order(OrderId id, String customerId, long amountCents, String reviewReason) {
    this.id = id;
    this.customerId = customerId;
    this.amountCents = amountCents;
    this.reviewReason = reviewReason;
  }

  public static Order place(OrderId id, String customerId, boolean firstOrder, long amountCents) {
    Order order = new Order(id, customerId, amountCents, null);
    order.registerEvent(new OrderPlaced(id, customerId, firstOrder, amountCents));
    return order;
  }

  public static Order reconstitute(
      OrderId id, String customerId, long amountCents, String reviewReason, long version) {
    Order order = new Order(id, customerId, amountCents, reviewReason);
    order.restoreVersion(version);
    return order;
  }

  /** Exists to show what the publish guard is for: calling this from a subscriber is refused. */
  public void flagForReview(String reason) {
    this.reviewReason = reason;
    registerEvent(new OrderFlagged(id, reason));
  }

  @Override
  public OrderId id() {
    return id;
  }

  public String customerId() {
    return customerId;
  }

  public long amountCents() {
    return amountCents;
  }

  public String reviewReason() {
    return reviewReason;
  }
}
