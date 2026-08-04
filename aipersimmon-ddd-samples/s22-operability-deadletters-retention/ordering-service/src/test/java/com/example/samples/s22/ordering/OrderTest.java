package com.example.samples.s22.ordering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aipersimmon.ddd.core.exception.DomainException;
import com.example.samples.s22.ordering.domain.Order;
import com.example.samples.s22.ordering.domain.OrderId;
import org.junit.jupiter.api.Test;

/**
 * The aggregate, at the cheapest layer that can answer for it (S18). Four assertions and no
 * infrastructure — which is the point being made about the domain in an operability sample: none of the
 * machinery the rest of this module is about reaches in here.
 */
class OrderTest {

  @Test
  void anorderIsPlacedWithWhatWasAskedFor() {
    Order order = Order.place(new OrderId("order-1"), "customer-1", "sku-keyboard", 2);

    assertThat(order.sku()).isEqualTo("sku-keyboard");
    assertThat(order.quantity()).isEqualTo(2);
  }

  @Test
  void anorderForNothingIsRefused() {
    assertThatThrownBy(() -> Order.place(new OrderId("order-1"), "customer-1", " ", 2))
        .isInstanceOf(DomainException.class);
  }

  @Test
  void anorderForNoneIsRefused() {
    assertThatThrownBy(() -> Order.place(new OrderId("order-1"), "customer-1", "sku-keyboard", 0))
        .isInstanceOf(DomainException.class);
  }

  @Test
  void anidIsRequired() {
    assertThatThrownBy(() -> new OrderId(" ")).isInstanceOf(IllegalArgumentException.class);
  }
}
