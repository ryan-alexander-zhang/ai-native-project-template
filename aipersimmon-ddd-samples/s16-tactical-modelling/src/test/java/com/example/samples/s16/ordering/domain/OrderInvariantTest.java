package com.example.samples.s16.ordering.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aipersimmon.ddd.core.rule.InvariantViolationException;
import org.junit.jupiter.api.Test;

/** The invariants: rules whose violation is a fault, each carrying a code that travels to the edge. */
class OrderInvariantTest {

  private static final Money PRICE = Money.of(Order.CURRENCY, "10.00");

  @Test
  void anOrderWithoutLinesCannotBePlaced() {
    Order order = Order.draft(new OrderId("order-1"), new CustomerId("customer-1"));

    assertThatThrownBy(order::place)
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("at least one line")
        .extracting(thrown -> ((InvariantViolationException) thrown).errorCode())
        .isEqualTo(java.util.Optional.of(OrderingErrorCode.ORDER_HAS_NO_LINES));
  }

  @Test
  void aRepeatedSkuIsRefusedAndLeavesNothingBehind() {
    Order order = Order.draft(new OrderId("order-1"), new CustomerId("customer-1"));
    order.addLine(new LineId("line-1"), new Sku("SKU-1"), PRICE, 1);

    assertThatThrownBy(() -> order.addLine(new LineId("line-2"), new Sku("SKU-1"), PRICE, 1))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("must not repeat a sku");

    // The invariant is checked against the prospective list, so the rejected line was never added.
    assertThat(order.lines()).hasSize(1);
  }

  @Test
  void aTotalOverTheContextCeilingCannotBePlaced() {
    Order order = Order.draft(new OrderId("order-1"), new CustomerId("customer-1"));
    order.addLine(new LineId("line-1"), new Sku("SKU-1"), Money.of(Order.CURRENCY, "600.00"), 100);

    assertThat(order.total()).isEqualTo(Money.of(Order.CURRENCY, "60000.00"));
    assertThatThrownBy(order::place)
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("exceeds the ceiling");
  }

  @Test
  void linesAreFrozenOnceTheOrderIsPlaced() {
    Order order = placedOrder();

    assertThatThrownBy(() -> order.addLine(new LineId("line-2"), new Sku("SKU-2"), PRICE, 1))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("cannot be changed");
    assertThatThrownBy(() -> order.amendLine(new LineId("line-1"), 5))
        .isInstanceOf(InvariantViolationException.class);
  }

  @Test
  void amendingALineThatIsNotThereIsACodedDomainFailure() {
    Order order = Order.draft(new OrderId("order-1"), new CustomerId("customer-1"));

    assertThatThrownBy(() -> order.amendLine(new LineId("nope"), 1))
        .isInstanceOf(com.aipersimmon.ddd.core.exception.DomainException.class)
        .hasMessageContaining("has no line nope");
  }

  private static Order placedOrder() {
    Order order = Order.draft(new OrderId("order-1"), new CustomerId("customer-1"));
    order.addLine(new LineId("line-1"), new Sku("SKU-1"), PRICE, 2);
    order.place();
    return order;
  }
}
