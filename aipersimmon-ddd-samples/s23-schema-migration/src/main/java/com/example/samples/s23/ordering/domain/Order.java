package com.example.samples.s23.ordering.domain;

import com.aipersimmon.ddd.core.annotation.AggregateRoot;
import com.aipersimmon.ddd.core.model.AbstractAggregateRoot;
import java.util.Optional;

/**
 * A placed order, in the shape the table has today.
 *
 * <p>The one concession to history is {@link #handling}, which may be absent — because V4 added the column
 * nullable and the rows that predate it are undecided until the backfill reaches them. That absence is
 * modelled as an {@link Optional} rather than defaulted to {@code STANDARD}, and the difference matters:
 * "we have not decided yet" and "we decided STANDARD" are different facts, and a default would make the
 * backfill unable to find its own work — as well as quietly mis-stating every legacy order that should
 * have been expedited.
 *
 * <p>A migration that fills a new column with a plausible default is the most common way to lose data that
 * was never there.
 */
@AggregateRoot
public final class Order extends AbstractAggregateRoot<OrderId> {

  private final OrderId id;
  private final String customerId;
  private final String sku;
  private final int quantity;
  private final ShipTo shipTo;
  private Handling handling;

  private Order(
      OrderId id, String customerId, String sku, int quantity, ShipTo shipTo, Handling handling) {
    this.id = id;
    this.customerId = customerId;
    this.sku = sku;
    this.quantity = quantity;
    this.shipTo = shipTo;
    this.handling = handling;
  }

  /** A new order decides its handling immediately, by the same rule the backfill uses. */
  public static Order place(
      OrderId id, String customerId, String sku, int quantity, ShipTo shipTo) {
    Order order = new Order(id, customerId, sku, quantity, shipTo, null);
    order.checkInvariant(new OrderIsForSomething(sku, quantity));
    order.handling = Handling.decide(quantity, shipTo);
    return order;
  }

  public static Order reconstitute(
      OrderId id,
      String customerId,
      String sku,
      int quantity,
      ShipTo shipTo,
      Handling handling,
      long version) {
    Order order = new Order(id, customerId, sku, quantity, shipTo, handling);
    order.restoreVersion(version);
    return order;
  }

  /**
   * Decides the handling of an order that predates the column.
   *
   * <p>Idempotent, and that is a requirement rather than a nicety: a backfill over a large table is
   * restarted — because it was interrupted, because someone ran it twice, because the pod was rescheduled
   * — and a step that is not safe to repeat turns a restart into a data question.
   *
   * @return true if this call decided something, so a caller can count the work and announce only what
   *     changed
   */
  public boolean decideHandling() {
    if (handling != null) {
      return false;
    }
    handling = Handling.decide(quantity, shipTo);
    return true;
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

  public ShipTo shipTo() {
    return shipTo;
  }

  /** Absent for a row the backfill has not reached. */
  public Optional<Handling> handling() {
    return Optional.ofNullable(handling);
  }
}
