package com.example.samples.s24;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.samples.s24.ordering.domain.Order;
import com.example.samples.s24.ordering.domain.OrderId;
import com.example.samples.s24.ordering.domain.OrderLine;
import com.example.samples.s24.sharedkernel.api.Money;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The order aggregate, with no coupons context anywhere in sight.
 *
 * <p>That is the test the design was for: discounts are constructed by hand here, because the aggregate takes a value and
 * has never heard of who computed it. A model that held the other context's port would need that context stubbed to be
 * tested at all, and "the model needs a stub" is how a boundary stops being one.
 */
class OrderTest {

  private static final Instant NOW = Instant.parse("2026-07-01T10:00:00Z");
  private static final List<OrderLine> ONE_LINE =
      List.of(new OrderLine(1, "SKU-1", 2, Money.of(1_500, "GBP")));

  @Test
  void anorderTotalsItsLinesAndSubtractsWhateverDiscountItWasGiven() {
    Order order =
        Order.place(new OrderId("o-1"), "cust-1", ONE_LINE, "SAVE10", Money.of(300, "GBP"), NOW);
    assertThat(order.gross()).isEqualTo(Money.of(3_000, "GBP"));
    assertThat(order.discount()).isEqualTo(Money.of(300, "GBP"));
    assertThat(order.total()).isEqualTo(Money.of(2_700, "GBP"));
    assertThat(order.couponCode()).contains("SAVE10");
  }

  @Test
  void noDiscountMeansNoDiscount() {
    Order order = Order.place(new OrderId("o-2"), "cust-1", ONE_LINE, null, null, NOW);
    assertThat(order.discount()).isEqualTo(Money.zero("GBP"));
    assertThat(order.total()).isEqualTo(Money.of(3_000, "GBP"));
    assertThat(order.couponCode()).isEmpty();
  }

  /**
   * A rule that belongs to this context however the number was arrived at.
   *
   * <p>Ordering checks the discount it is handed, because "this order's total must not be negative" is ordering's
   * invariant. Trusting an upstream context to have got it right is how a bug in one service becomes a credit note in
   * another.
   */
  @Test
  void adiscountLargerThanTheOrderIsRefused() {
    assertThatThrownBy(
            () ->
                Order.place(
                    new OrderId("o-3"), "cust-1", ONE_LINE, "TOOMUCH", Money.of(9_999, "GBP"), NOW))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("is more than the order's");
  }

  @Test
  void anegativeDiscountIsRefused() {
    assertThatThrownBy(
            () ->
                Order.place(
                    new OrderId("o-4"), "cust-1", ONE_LINE, "ODD", Money.of(-100, "GBP"), NOW))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must not be negative");
  }

  @Test
  void anorderNeedsACustomerAndALine() {
    assertThatThrownBy(() -> Order.place(new OrderId("o-5"), " ", ONE_LINE, null, null, NOW))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Order.place(new OrderId("o-6"), "cust-1", List.of(), null, null, NOW))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
