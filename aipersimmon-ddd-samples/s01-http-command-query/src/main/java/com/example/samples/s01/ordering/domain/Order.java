package com.example.samples.s01.ordering.domain;

import com.aipersimmon.ddd.core.annotation.AggregateRoot;
import com.aipersimmon.ddd.core.model.AbstractAggregateRoot;
import com.aipersimmon.ddd.core.state.Transitions;
import java.util.List;

/**
 * An order, kept deliberately small: this sample is about the path a request takes, not about how to
 * model. Aggregate design — where invariants belong, how boundaries are drawn — is S16.
 */
@AggregateRoot
public final class Order extends AbstractAggregateRoot<OrderId> {

  /** Not thread-safe, so it is built once and treated as frozen. The refusal code belongs to the
   * destination state, which is why CONFIRMED carries it. */
  private static final Transitions<OrderStatus> TRANSITIONS =
      Transitions.<OrderStatus>of()
          .allow(OrderStatus.PLACED, OrderStatus.CONFIRMED, OrderingErrorCode.ORDER_NOT_CONFIRMABLE);

  private final OrderId id;
  private final String customerId;
  private final List<OrderLine> lines;
  private OrderStatus status;

  private Order(OrderId id, String customerId, List<OrderLine> lines, OrderStatus status) {
    this.id = id;
    this.customerId = customerId;
    this.lines = List.copyOf(lines);
    this.status = status;
  }

  public static Order place(OrderId id, String customerId, List<OrderLine> lines) {
    Order order = new Order(id, customerId, lines, OrderStatus.PLACED);
    order.checkInvariant(new OrderHasLines(order.lines));
    order.registerEvent(new OrderPlaced(id, customerId));
    return order;
  }

  /**
   * Rebuilds a persisted order. {@code restoreVersion} is protected on the base class, so only the
   * aggregate can call it — and it must: an order rebuilt at version 0 would take the insert branch
   * on the next save and collide on its own primary key.
   */
  public static Order reconstitute(
      OrderId id, String customerId, List<OrderLine> lines, OrderStatus status, long version) {
    Order order = new Order(id, customerId, lines, status);
    order.restoreVersion(version);
    return order;
  }

  public void confirm() {
    TRANSITIONS.check(status, OrderStatus.CONFIRMED);
    this.status = OrderStatus.CONFIRMED;
    registerEvent(new OrderConfirmed(id));
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

  public OrderStatus status() {
    return status;
  }
}
