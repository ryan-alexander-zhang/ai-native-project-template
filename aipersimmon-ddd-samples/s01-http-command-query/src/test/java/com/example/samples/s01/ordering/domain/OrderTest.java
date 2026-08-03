package com.example.samples.s01.ordering.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aipersimmon.ddd.core.rule.InvariantViolationException;
import com.aipersimmon.ddd.core.state.IllegalStateTransitionException;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The domain layer, tested with no Spring and no database. */
class OrderTest {

  private static final OrderId ID = new OrderId("order-1");
  private static final List<OrderLine> ONE_LINE = List.of(new OrderLine("SKU-1", 2));

  @Test
  void placingRegistersAnEventAndStartsAtVersionZero() {
    Order order = Order.place(ID, "customer-1", ONE_LINE);

    assertThat(order.status()).isEqualTo(OrderStatus.PLACED);
    assertThat(order.domainEvents()).containsExactly(new OrderPlaced(ID, "customer-1"));
    // Never persisted, so the next save must take the insert branch.
    assertThat(order.version()).isZero();
  }

  @Test
  void anOrderWithoutLinesIsRefusedByItsInvariant() {
    assertThatThrownBy(() -> Order.place(ID, "customer-1", List.of()))
        .isInstanceOf(InvariantViolationException.class)
        .hasMessageContaining("at least one line");
  }

  @Test
  void confirmingTwiceIsRefusedByTheTransitionTable() {
    Order order = Order.place(ID, "customer-1", ONE_LINE);
    order.confirm();

    assertThatThrownBy(order::confirm).isInstanceOf(IllegalStateTransitionException.class);
  }

  @Test
  void aReconstitutedOrderCarriesItsPersistedVersion() {
    Order order =
        Order.reconstitute(ID, "customer-1", ONE_LINE, OrderStatus.PLACED, 7L);

    assertThat(order.version()).isEqualTo(7L);
    // Rebuilding is not a business event.
    assertThat(order.domainEvents()).isEmpty();
  }
}
