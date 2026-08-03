package com.example.samples.s11.ordering.domain;

import com.aipersimmon.ddd.core.annotation.AggregateRoot;
import com.aipersimmon.ddd.core.model.AbstractAggregateRoot;
import com.aipersimmon.ddd.core.state.Transitions;
import java.time.Instant;

/**
 * An order that must be paid by a deadline.
 *
 * <p>The transition table is the reason the sweep sends commands instead of running one {@code
 * UPDATE}: closing an order is a state change with a rule ("only an open one closes") and a
 * consequence (an event downstream reacts to). A batch statement can express the write but neither
 * the rule nor the consequence, and it cannot be told that the customer paid two seconds ago.
 */
@AggregateRoot
public final class Order extends AbstractAggregateRoot<OrderId> {

  /** Built once and treated as frozen. The refusal code belongs to the destination state. */
  private static final Transitions<OrderStatus> TRANSITIONS =
      Transitions.<OrderStatus>of()
          .allow(OrderStatus.PLACED, OrderStatus.PAID, OrderingErrorCode.ORDER_NOT_PAYABLE)
          .allow(OrderStatus.PLACED, OrderStatus.CLOSED, OrderingErrorCode.ORDER_NOT_CLOSABLE);

  private final OrderId id;
  private final String customerId;
  private final Instant paymentDueAt;
  private OrderStatus status;

  private Order(OrderId id, String customerId, Instant paymentDueAt, OrderStatus status) {
    this.id = id;
    this.customerId = customerId;
    this.paymentDueAt = paymentDueAt;
    this.status = status;
  }

  public static Order place(OrderId id, String customerId, Instant paymentDueAt) {
    return new Order(id, customerId, paymentDueAt, OrderStatus.PLACED);
  }

  /** Rebuilds a persisted order; the version has to come back or the next save would insert. */
  public static Order reconstitute(
      OrderId id, String customerId, Instant paymentDueAt, OrderStatus status, long version) {
    Order order = new Order(id, customerId, paymentDueAt, status);
    order.restoreVersion(version);
    return order;
  }

  public void pay() {
    TRANSITIONS.check(status, OrderStatus.PAID);
    this.status = OrderStatus.PAID;
  }

  /**
   * Closes an unpaid order.
   *
   * <p>This is where the sweep's decision is actually made. The scan said "this one looked expired a
   * moment ago"; the table here says whether it still is. An order paid between the two is refused,
   * which is exactly the answer a batch wants — not an error, and not a close.
   */
  public void close() {
    TRANSITIONS.check(status, OrderStatus.CLOSED);
    this.status = OrderStatus.CLOSED;
    registerEvent(new OrderClosed(id, customerId));
  }

  @Override
  public OrderId id() {
    return id;
  }

  public String customerId() {
    return customerId;
  }

  public Instant paymentDueAt() {
    return paymentDueAt;
  }

  public OrderStatus status() {
    return status;
  }
}
