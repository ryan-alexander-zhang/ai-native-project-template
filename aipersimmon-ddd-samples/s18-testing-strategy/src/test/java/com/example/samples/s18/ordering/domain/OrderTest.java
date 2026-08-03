package com.example.samples.s18.ordering.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aipersimmon.ddd.core.rule.InvariantViolationException;
import com.aipersimmon.ddd.core.state.IllegalStateTransitionException;
import org.junit.jupiter.api.Test;

/**
 * Layer 1 — the domain, with nothing else.
 *
 * <p>No Spring, no database, no doubles: the rules are the only thing under test, so anything else in
 * the fixture would be noise that slows the suite and dilutes the failure message. If a rule needs a
 * mock to be tested, that is a signal the rule is in the wrong place, not that the test needs help.
 */
class OrderTest {

  private static final OrderId ID = new OrderId("order-1");

  @Test
  void placingRegistersTheFactAndLeavesTheVersionUnpersisted() {
    Order order = Order.place(ID, "customer-1", 500);

    assertThat(order.status()).isEqualTo(OrderStatus.PLACED);
    assertThat(order.domainEvents())
        .containsExactly(new OrderPlacedInContext(ID, "customer-1", 500));
    assertThat(order.version()).isZero();
  }

  @Test
  void anOrderWithoutAnAmountIsRefused() {
    assertThatThrownBy(() -> Order.place(ID, "customer-1", 0))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("positive amount");
  }

  @Test
  void confirmingTwiceIsRefusedByTheTransitionTable() {
    Order order = Order.place(ID, "customer-1", 500);
    order.confirm();

    assertThatThrownBy(order::confirm).isInstanceOf(IllegalStateTransitionException.class);
  }
}
