package com.example.samples.s16.ordering.domain;

import com.aipersimmon.ddd.core.annotation.AggregateRoot;
import com.aipersimmon.ddd.core.exception.DomainException;
import com.aipersimmon.ddd.core.model.AbstractAggregateRoot;
import com.aipersimmon.ddd.core.state.Transitions;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * An order: the aggregate root, and the only way into its lines.
 *
 * <p>The boundary is drawn where the invariants reach. Every rule enforced here — at least one line,
 * no repeated sku, a total under the context's ceiling, lines frozen once placed — reads nothing but
 * this order's own data, so all of it must hold within one transaction. A rule about the customer's
 * credit limit would span two aggregates and deliberately is not here.
 */
@AggregateRoot
public final class Order extends AbstractAggregateRoot<OrderId> {

  public static final String CURRENCY = "CNY";

  /** A rule of the context, not a per-order setting, so it is a constant of the model. */
  public static final Money CEILING = Money.of(CURRENCY, "50000.00");

  /**
   * Built completely at class-initialisation time and frozen from then on: the table is not
   * synchronized, so declaring transitions later would be a data race.
   *
   * <p>Both edges into CANCELLED carry the same refusal code, because the code belongs to the
   * destination — "not in a state to be cancelled" is about where the caller tried to go. Declaring
   * them with different codes fails here, at startup, rather than at some later request.
   */
  private static final Transitions<OrderStatus> TRANSITIONS =
      Transitions.<OrderStatus>of()
          .allow(OrderStatus.DRAFT, OrderStatus.PLACED, OrderingErrorCode.ORDER_NOT_PLACEABLE)
          .allow(OrderStatus.PLACED, OrderStatus.PAID, OrderingErrorCode.ORDER_NOT_PAYABLE)
          .allow(OrderStatus.DRAFT, OrderStatus.CANCELLED, OrderingErrorCode.ORDER_NOT_CANCELLABLE)
          .allow(
              OrderStatus.PLACED, OrderStatus.CANCELLED, OrderingErrorCode.ORDER_NOT_CANCELLABLE);

  private final OrderId id;
  private final CustomerId customerId;
  private final List<OrderLine> lines = new ArrayList<>();
  private OrderStatus status;

  private Order(OrderId id, CustomerId customerId, OrderStatus status) {
    this.id = Objects.requireNonNull(id, "id");
    this.customerId = Objects.requireNonNull(customerId, "customerId");
    this.status = Objects.requireNonNull(status, "status");
  }

  /** The default kind of factory: a static method on the aggregate with a name from the language. */
  public static Order draft(OrderId id, CustomerId customerId) {
    return new Order(id, customerId, OrderStatus.DRAFT);
  }

  /**
   * The rebuild factory. Not a business action: it registers no event, and it is the only place that
   * can call {@code restoreVersion}, which is {@code protected} so a repository cannot inject a
   * version behind the aggregate's back. Why that matters is S17.
   */
  public static Order reconstitute(
      OrderId id,
      CustomerId customerId,
      List<OrderLine> lines,
      OrderStatus status,
      long version) {
    Order order = new Order(id, customerId, status);
    order.lines.addAll(lines);
    order.restoreVersion(version);
    return order;
  }

  public void addLine(LineId lineId, Sku sku, Money unitPrice, int quantity) {
    checkInvariant(new OrderLinesAreStillOpen(status));
    OrderLine candidate = new OrderLine(lineId, sku, unitPrice, quantity);
    // Checked against the prospective list, before the mutation lands. Mutating first and checking
    // after would leave a rejected line on the aggregate whenever the caller catches the exception.
    List<OrderLine> prospective = new ArrayList<>(lines);
    prospective.add(candidate);
    checkInvariant(new OrderLinesHaveDistinctSkus(prospective));
    lines.add(candidate);
  }

  public void amendLine(LineId lineId, int newQuantity) {
    checkInvariant(new OrderLinesAreStillOpen(status));
    lineOf(lineId).amendQuantity(newQuantity);
  }

  public void place() {
    TRANSITIONS.check(status, OrderStatus.PLACED);
    checkInvariant(new OrderHasAtLeastOneLine(lines));
    checkInvariant(new OrderTotalWithinCeiling(total(), CEILING));
    this.status = OrderStatus.PLACED;
    registerEvent(new OrderPlaced(id, customerId, total()));
  }

  public void pay() {
    TRANSITIONS.check(status, OrderStatus.PAID);
    this.status = OrderStatus.PAID;
    registerEvent(new OrderPaid(id, total()));
  }

  public void cancel() {
    TRANSITIONS.check(status, OrderStatus.CANCELLED);
    this.status = OrderStatus.CANCELLED;
    registerEvent(new OrderCancelled(id));
  }

  public Money total() {
    return lines.stream().map(OrderLine::subtotal).reduce(Money.zero(CURRENCY), Money::plus);
  }

  /** A copy: outside code reads the lines, it does not reach past the root to change them. */
  public List<OrderLine> lines() {
    return List.copyOf(lines);
  }

  public OrderStatus status() {
    return status;
  }

  public CustomerId customerId() {
    return customerId;
  }

  @Override
  public OrderId id() {
    return id;
  }

  private OrderLine lineOf(LineId lineId) {
    return lines.stream()
        .filter(line -> line.id().equals(lineId))
        .findFirst()
        .orElseThrow(
            () ->
                new DomainException(
                    OrderingErrorCode.ORDER_LINE_NOT_FOUND,
                    "order " + id.value() + " has no line " + lineId.value()));
  }
}
