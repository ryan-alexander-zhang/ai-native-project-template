package com.example.samples.s18.ordering.domain;

import com.aipersimmon.ddd.core.annotation.AggregateRoot;
import com.aipersimmon.ddd.core.model.AbstractAggregateRoot;
import com.aipersimmon.ddd.core.state.Transitions;

/** Small on purpose: this sample is about how each layer is tested, not about the model. */
@AggregateRoot
public final class Order extends AbstractAggregateRoot<OrderId> {

  private static final Transitions<OrderStatus> TRANSITIONS =
      Transitions.<OrderStatus>of()
          .allow(OrderStatus.PLACED, OrderStatus.CONFIRMED, OrderingErrorCode.ORDER_NOT_CONFIRMABLE);

  private final OrderId id;
  private final String customerId;
  private final long amountCents;
  private OrderStatus status;

  private Order(OrderId id, String customerId, long amountCents, OrderStatus status) {
    this.id = id;
    this.customerId = customerId;
    this.amountCents = amountCents;
    this.status = status;
  }

  public static Order place(OrderId id, String customerId, long amountCents) {
    Order order = new Order(id, customerId, amountCents, OrderStatus.PLACED);
    order.checkInvariant(new OrderHasAnAmount(amountCents));
    order.registerEvent(new OrderPlacedInContext(id, customerId, amountCents));
    return order;
  }

  public static Order reconstitute(
      OrderId id, String customerId, long amountCents, OrderStatus status, long version) {
    Order order = new Order(id, customerId, amountCents, status);
    order.restoreVersion(version);
    return order;
  }

  public void confirm() {
    TRANSITIONS.check(status, OrderStatus.CONFIRMED);
    this.status = OrderStatus.CONFIRMED;
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

  public OrderStatus status() {
    return status;
  }
}
