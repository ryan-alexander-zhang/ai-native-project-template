package com.example.samples.s24.ordering.domain;

import com.aipersimmon.ddd.core.annotation.AggregateRoot;
import com.aipersimmon.ddd.core.model.AbstractAggregateRoot;
import com.example.samples.s24.sharedkernel.api.Money;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * An order, priced.
 *
 * <p>The interesting thing about this class is a negative: <strong>it does not know the coupons context exists.</strong>
 * Not through a client, not through a port, not even through {@code coupons.api}. It is handed a code and an amount, as
 * values, and it records both.
 *
 * <p>That is not purism. Three things follow from it, and all three are the difference between a boundary and a
 * decoration:
 *
 * <ul>
 *   <li>the aggregate is testable with no coupons context at all — {@code OrderTest} constructs discounts by hand;
 *   <li>there is no place in the model where a network call could later appear. A domain object holding a
 *       {@code CouponQuotes} would be a domain object that can time out;
 *   <li>the decision about <em>when</em> to ask, and what to do when the answer does not come, is forced up into the
 *       application layer, which is the only layer that can reason about a transaction and a retry.
 * </ul>
 *
 * <p>{@code ArchitectureTest.nodomainKnowsAnotherContextExists} pins it. The library's own isolation rule would happily
 * allow {@code ordering.domain} → {@code coupons.api}; this sample does not, and the difference is worth being explicit
 * about rather than inheriting silently.
 *
 * <p>The coupon code is stored as a {@code String}, not as a {@code CouponCode}, for the same reason — and it is the
 * one place where that rule costs something real. Ordering loses the validation that {@code CouponCode} carries, and
 * has to trust that the application layer validated it on the way in. The alternative is a domain that imports another
 * context's type; this sample takes the trade, names it, and puts the parsing at the boundary
 * ({@code PlaceOrderHandler}) where the value arrives.
 */
@AggregateRoot
public final class Order extends AbstractAggregateRoot<OrderId> {

  private final OrderId id;
  private final String customerId;
  private final List<OrderLine> lines;
  private final Money gross;
  private final Instant placedAt;

  private OrderStatus status;
  private Money discount;
  private String couponCode;

  private Order(
      OrderId id,
      String customerId,
      List<OrderLine> lines,
      Money gross,
      Money discount,
      String couponCode,
      OrderStatus status,
      Instant placedAt) {
    this.id = id;
    this.customerId = customerId;
    this.lines = List.copyOf(lines);
    this.gross = gross;
    this.discount = discount;
    this.couponCode = couponCode;
    this.status = status;
    this.placedAt = placedAt;
  }

  /**
   * Place it, at a price.
   *
   * <p>The discount arrives as a value, already decided by somebody else. The aggregate's job is to check that it makes
   * sense here — same currency, not negative, not more than the basket — which is a rule that belongs to <em>this</em>
   * context however the number was arrived at. A discount larger than the order is the sort of thing an upstream bug
   * produces, and refusing it here is cheaper than a credit note.
   */
  public static Order place(
      OrderId id,
      String customerId,
      List<OrderLine> lines,
      String couponCode,
      Money discount,
      Instant at) {
    if (customerId == null || customerId.isBlank()) {
      throw new IllegalArgumentException("an order needs a customer");
    }
    if (lines.isEmpty()) {
      throw new IllegalArgumentException("an order needs at least one line");
    }
    Money gross = totalOf(lines);
    Money applied = discount == null ? Money.zero(gross.currency()) : discount;
    if (applied.isNegative()) {
      throw new IllegalArgumentException("a discount must not be negative, was " + applied);
    }
    if (applied.isGreaterThan(gross)) {
      throw new IllegalArgumentException(
          "a discount of " + applied + " is more than the order's " + gross);
    }
    return new Order(
        id, customerId, lines, gross, applied, couponCode, OrderStatus.PLACED, at);
  }

  public static Order reconstitute(
      OrderId id,
      String customerId,
      List<OrderLine> lines,
      Money gross,
      Money discount,
      String couponCode,
      OrderStatus status,
      Instant placedAt,
      long version) {
    Order order =
        new Order(id, customerId, lines, gross, discount, couponCode, status, placedAt);
    order.restoreVersion(version);
    return order;
  }

  public boolean cancel() {
    if (status == OrderStatus.CANCELLED) {
      return false;
    }
    status = OrderStatus.CANCELLED;
    return true;
  }

  public Money total() {
    return gross.minus(discount);
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

  public Money gross() {
    return gross;
  }

  public Money discount() {
    return discount;
  }

  public Optional<String> couponCode() {
    return Optional.ofNullable(couponCode);
  }

  public OrderStatus status() {
    return status;
  }

  public Instant placedAt() {
    return placedAt;
  }

  private static Money totalOf(List<OrderLine> lines) {
    Money sum = Money.zero(lines.get(0).unitPrice().currency());
    for (OrderLine line : lines) {
      sum = sum.plus(line.lineTotal());
    }
    return sum;
  }
}
