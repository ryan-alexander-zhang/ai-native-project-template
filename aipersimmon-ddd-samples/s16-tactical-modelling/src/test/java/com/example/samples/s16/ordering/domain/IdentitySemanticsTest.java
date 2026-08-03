package com.example.samples.s16.ordering.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The difference between an entity and a value object, made executable — plus the three details of
 * {@code AbstractAggregateRoot}'s final equality that are easy to be surprised by.
 */
class IdentitySemanticsTest {

  private static final Money PRICE = Money.of(Order.CURRENCY, "10.00");

  @Test
  void anEntityIsTheSameLineAfterItsQuantityChanges() {
    Order order = draftWithOneLine();
    OrderLine before = order.lines().get(0);

    order.amendLine(new LineId("line-1"), 9);
    OrderLine after = order.lines().get(0);

    assertThat(after.quantity()).isEqualTo(9);
    // Identity equality: attributes moved, the line did not.
    assertThat(after).isEqualTo(before);
  }

  @Test
  void twoLinesWithTheSameAttributesButDifferentIdsAreDifferentLines() {
    Order order = Order.draft(new OrderId("order-1"), new CustomerId("customer-1"));
    order.addLine(new LineId("line-1"), new Sku("SKU-1"), PRICE, 1);
    order.addLine(new LineId("line-2"), new Sku("SKU-2"), PRICE, 1);

    List<OrderLine> lines = order.lines();

    assertThat(lines.get(0)).isNotEqualTo(lines.get(1));
    assertThat(Set.copyOf(lines)).hasSize(2);
  }

  @Test
  void aggregateEqualityIsByIdAndIgnoresVersionAndEvents() {
    Order placed = draftWithOneLine();
    placed.place();
    Order rebuilt =
        Order.reconstitute(
            new OrderId("order-1"),
            new CustomerId("customer-1"),
            List.of(),
            OrderStatus.PAID,
            7L);

    // Same id, wildly different state: still the same order. Version and recorded events are
    // persistence and lifecycle state, not identity.
    assertThat(placed.domainEvents()).isNotEmpty();
    assertThat(placed.version()).isZero();
    assertThat(rebuilt.version()).isEqualTo(7L);
    assertThat(placed).isEqualTo(rebuilt);
    assertThat(placed.hashCode()).isEqualTo(rebuilt.hashCode());
  }

  @Test
  void differentAggregateIdsAreDifferentAggregates() {
    Order one = Order.draft(new OrderId("order-1"), new CustomerId("customer-1"));
    Order two = Order.draft(new OrderId("order-2"), new CustomerId("customer-1"));

    assertThat(one).isNotEqualTo(two);
    assertThat(Set.of(one, two)).hasSize(2);
  }

  private static Order draftWithOneLine() {
    Order order = Order.draft(new OrderId("order-1"), new CustomerId("customer-1"));
    order.addLine(new LineId("line-1"), new Sku("SKU-1"), PRICE, 2);
    return order;
  }
}
