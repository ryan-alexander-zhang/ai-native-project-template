package com.example.samples.s12.ordering.domain;

import com.aipersimmon.ddd.core.annotation.AggregateRoot;
import com.aipersimmon.ddd.core.model.AbstractAggregateRoot;
import java.time.Instant;
import java.util.List;

/**
 * An order and its lines — the write model, and the only truth here.
 *
 * <p>Two things about it are worth reading for what S12 is about.
 *
 * <p><strong>Each line carries the product name as it was at purchase.</strong> Not a lookup, not a join:
 * a copied value. The invoice has to say what the customer agreed to buy, so a rename in the catalogue two
 * months later must not rewrite it. That makes it a business fact belonging to this aggregate, and copying
 * it is correct rather than denormalisation.
 *
 * <p><strong>Nothing here mentions the projection.</strong> The aggregate records that it was placed and
 * that it was paid; what any read model does about that is not its concern. If this class ever gains a
 * {@code projectedAt} or a {@code listRowDirty}, the read side has started leaking into the write side and
 * the projection has stopped being disposable.
 */
@AggregateRoot
public final class Order extends AbstractAggregateRoot<OrderId> {

  private final OrderId id;
  private final String customerId;
  private final List<Line> lines;
  private final Instant placedAt;

  private OrderStatus status;
  private Instant paidAt;

  private Order(
      OrderId id,
      String customerId,
      List<Line> lines,
      Instant placedAt,
      OrderStatus status,
      Instant paidAt) {
    this.id = id;
    this.customerId = customerId;
    this.lines = List.copyOf(lines);
    this.placedAt = placedAt;
    this.status = status;
    this.paidAt = paidAt;
  }

  /** Place a new order. Records the fact; the projection reacts to it. */
  public static Order place(OrderId id, String customerId, List<Line> lines, Instant now) {
    if (lines.isEmpty()) {
      throw new IllegalArgumentException("an order needs at least one line");
    }
    Order order = new Order(id, customerId, lines, now, OrderStatus.PLACED, null);
    order.registerEvent(new OrderPlaced(id, customerId));
    return order;
  }

  public static Order reconstitute(
      OrderId id,
      String customerId,
      List<Line> lines,
      Instant placedAt,
      OrderStatus status,
      Instant paidAt,
      long version) {
    Order order = new Order(id, customerId, lines, placedAt, status, paidAt);
    order.restoreVersion(version);
    return order;
  }

  /**
   * Mark it paid.
   *
   * @return false when it already was, so a retried payment notification is silence rather than a second
   *     event the projection has to absorb.
   */
  public boolean markPaid(Instant now) {
    if (status == OrderStatus.PAID) {
      return false;
    }
    this.status = OrderStatus.PAID;
    this.paidAt = now;
    registerEvent(new OrderPaid(id, customerId));
    return true;
  }

  @Override
  public OrderId id() {
    return id;
  }

  public String customerId() {
    return customerId;
  }

  public List<Line> lines() {
    return lines;
  }

  public Instant placedAt() {
    return placedAt;
  }

  public OrderStatus status() {
    return status;
  }

  public Instant paidAt() {
    return paidAt;
  }

  public long totalMinor() {
    return lines.stream().mapToLong(line -> line.unitPriceMinor() * line.quantity()).sum();
  }

  /**
   * One line.
   *
   * @param nameAtPurchase what this product was called when the order was placed. Frozen; see the class
   *     javadoc.
   */
  public record Line(String sku, int quantity, long unitPriceMinor, String nameAtPurchase) {

    public Line {
      if (quantity <= 0) {
        throw new IllegalArgumentException("quantity must be positive: " + quantity);
      }
    }
  }
}
