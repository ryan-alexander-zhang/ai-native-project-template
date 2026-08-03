package com.example.samples.s06.ordering.domain;

import com.aipersimmon.ddd.core.annotation.AggregateRoot;
import com.aipersimmon.ddd.core.model.AbstractAggregateRoot;

/**
 * A placed order.
 *
 * <p>Note what is <strong>not</strong> here: any mention of risk. The aggregate does not call the risk
 * service, does not hold a "riskApproved" flag, and does not know the service exists. A domain model that
 * makes a network call has made its invariants depend on somebody else's uptime, and it can no longer be
 * tested without a stub — which is the practical reason the rule "the domain does not call out" is worth
 * keeping even when it looks convenient to break.
 *
 * <p>Where the risk answer belongs is one layer out: a precondition of the use case, screened before the
 * transaction opens. See {@code RiskPrecheck}.
 */
@AggregateRoot
public final class Order extends AbstractAggregateRoot<OrderId> {

  private final OrderId id;
  private final String customerId;
  private final long amountCents;

  private Order(OrderId id, String customerId, long amountCents) {
    this.id = id;
    this.customerId = customerId;
    this.amountCents = amountCents;
  }

  public static Order place(OrderId id, String customerId, long amountCents) {
    Order order = new Order(id, customerId, amountCents);
    order.checkInvariant(new AmountIsPositive(amountCents));
    return order;
  }

  public static Order reconstitute(
      OrderId id, String customerId, long amountCents, long version) {
    Order order = new Order(id, customerId, amountCents);
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

  public long amountCents() {
    return amountCents;
  }
}
