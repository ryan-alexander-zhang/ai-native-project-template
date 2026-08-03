package com.example.samples.s17.ordering.domain;

import com.aipersimmon.ddd.core.annotation.AggregateRoot;
import com.aipersimmon.ddd.core.model.AbstractAggregateRoot;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * An order shaped to exercise the four things that make mapping hard: a nullable field the domain can
 * empty ({@code note}), a value object worth flattening ({@link Money}), one worth serialising
 * ({@link ShippingAddress}), and a child collection of entities whose identities must survive a write.
 */
@AggregateRoot
public final class Order extends AbstractAggregateRoot<OrderId> {

  private final OrderId id;
  private final String customerId;
  private final List<OrderLine> lines = new ArrayList<>();
  private ShippingAddress shippingAddress;
  private OrderStatus status;

  /** Nullable on purpose: clearing it is the write MyBatis-Plus would otherwise drop. */
  private String note;

  private Order(
      OrderId id,
      String customerId,
      ShippingAddress shippingAddress,
      OrderStatus status,
      String note) {
    this.id = Objects.requireNonNull(id, "id");
    this.customerId = Objects.requireNonNull(customerId, "customerId");
    this.shippingAddress = Objects.requireNonNull(shippingAddress, "shippingAddress");
    this.status = status;
    this.note = note;
  }

  public static Order draft(OrderId id, String customerId, ShippingAddress shippingAddress) {
    return new Order(id, customerId, shippingAddress, OrderStatus.DRAFT, null);
  }

  /**
   * The rebuild factory, and the only place {@code restoreVersion} can be called from — it is
   * {@code protected} on the base class. Forget it and the aggregate stays at version 0, which is how
   * an update turns into an insert; {@code AggregateMappingTest} reproduces exactly that failure.
   */
  public static Order reconstitute(
      OrderId id,
      String customerId,
      ShippingAddress shippingAddress,
      OrderStatus status,
      String note,
      List<OrderLine> lines,
      long version) {
    Order order = new Order(id, customerId, shippingAddress, status, note);
    order.lines.addAll(lines);
    order.restoreVersion(version);
    return order;
  }

  public void addLine(LineId lineId, String sku, Money unitPrice, int quantity) {
    lines.add(new OrderLine(lineId, sku, unitPrice, quantity));
  }

  public void amendLine(LineId lineId, int newQuantity) {
    lineOf(lineId).amendQuantity(newQuantity);
  }

  public void removeLine(LineId lineId) {
    lines.remove(lineOf(lineId));
  }

  public void noteFor(String note) {
    this.note = note;
  }

  /** The interesting write: after this the column must actually become NULL. */
  public void clearNote() {
    this.note = null;
  }

  public void moveTo(ShippingAddress address) {
    this.shippingAddress = Objects.requireNonNull(address, "address");
  }

  public void place() {
    this.status = OrderStatus.PLACED;
  }

  public Money total() {
    return lines.stream().map(OrderLine::subtotal).reduce(Money.of("CNY", 0), Money::plus);
  }

  @Override
  public OrderId id() {
    return id;
  }

  public String customerId() {
    return customerId;
  }

  public List<OrderLine> lines() {
    return List.copyOf(lines);
  }

  public ShippingAddress shippingAddress() {
    return shippingAddress;
  }

  public OrderStatus status() {
    return status;
  }

  public String note() {
    return note;
  }

  private OrderLine lineOf(LineId lineId) {
    return lines.stream()
        .filter(line -> line.id().equals(lineId))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("no line " + lineId.value()));
  }
}
