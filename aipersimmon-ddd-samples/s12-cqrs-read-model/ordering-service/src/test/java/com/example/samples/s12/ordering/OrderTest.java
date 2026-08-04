package com.example.samples.s12.ordering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.samples.s12.ordering.domain.Order;
import com.example.samples.s12.ordering.domain.OrderId;
import com.example.samples.s12.ordering.domain.OrderStatus;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The write model's own rules. Short, because S12's subject is on the other side. */
class OrderTest {

  private static final Instant NOW = Instant.parse("2026-08-04T10:00:00Z");

  private static Order placed() {
    return Order.place(
        new OrderId("order-1"),
        "customer-1",
        List.of(
            new Order.Line("sku-keyboard", 2, 1500, "Mechanical Keyboard"),
            new Order.Line("sku-mouse", 1, 1000, "Wireless Mouse")),
        NOW);
  }

  @Test
  void thetotalIsTheSumOfTheLines() {
    assertThat(placed().totalMinor()).isEqualTo(4000);
  }

  @Test
  void anorderWithoutLinesIsRefused() {
    assertThatThrownBy(() -> Order.place(new OrderId("order-x"), "customer-1", List.of(), NOW))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void payingTwiceRecordsOneEvent() {
    Order order = placed();

    assertThat(order.markPaid(NOW)).isTrue();
    // The second call is false, so the caller does not save and the projection is not asked to recompute a
    // row that has not changed. Idempotence on the write side is what keeps the read side quiet.
    assertThat(order.markPaid(NOW)).isFalse();
    assertThat(order.status()).isEqualTo(OrderStatus.PAID);
  }

  @Test
  void alineRemembersTheNameItWasBoughtUnder() {
    // The frozen half of the sample's central pair. The projection shows the current name; this never
    // changes again, whatever the catalogue does.
    assertThat(placed().lines().getFirst().nameAtPurchase()).isEqualTo("Mechanical Keyboard");
  }
}
