package com.example.samples.s23;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aipersimmon.ddd.core.exception.DomainException;
import com.example.samples.s23.ordering.domain.Handling;
import com.example.samples.s23.ordering.domain.Order;
import com.example.samples.s23.ordering.domain.OrderId;
import com.example.samples.s23.ordering.domain.ShipTo;
import org.junit.jupiter.api.Test;

/**
 * The rule and the undecided state, at the cheapest layer that can answer for them (S18).
 *
 * <p>These four tests are the reason the backfill is a command: everything asserted here is asserted about the
 * <em>domain</em>, and a migration written in SQL would have had to restate all of it in a language none of
 * these tests can reach.
 */
class OrderTest {

  private static final ShipTo LONDON = new ShipTo("12 Baker Street", "London");
  private static final ShipTo REMOTE = new ShipTo("1 Commercial Street", "Shetland");

  @Test
  void anewOrderDecidesItsHandlingImmediately() {
    Order order = Order.place(new OrderId("order-1"), "customer-1", "sku-keyboard", 2, LONDON);

    assertThat(order.handling()).contains(Handling.STANDARD);
  }

  @Test
  void aremoteDestinationOrALargeQuantityIsExpedited() {
    assertThat(Handling.decide(1, REMOTE)).isEqualTo(Handling.EXPEDITED);
    assertThat(Handling.decide(10, LONDON)).isEqualTo(Handling.EXPEDITED);
    assertThat(Handling.decide(9, LONDON)).isEqualTo(Handling.STANDARD);
  }

  /**
   * A row that predates the column is undecided, and says so.
   *
   * <p>Not defaulted to STANDARD, which is the mistake V4 exists to avoid: "we have not decided" and "we decided
   * STANDARD" are different facts, and collapsing them would both hide the backfill's work and mis-state every
   * legacy order that should have been expedited.
   */
  @Test
  void alegacyOrderIsUndecidedUntilTheBackfillReachesIt() {
    Order order =
        Order.reconstitute(new OrderId("order-1"), "customer-1", "sku-keyboard", 1, REMOTE, null, 1);

    assertThat(order.handling()).isEmpty();

    assertThat(order.decideHandling()).isTrue();
    assertThat(order.handling()).contains(Handling.EXPEDITED);
    // Idempotent, because a backfill gets restarted.
    assertThat(order.decideHandling()).isFalse();
  }

  @Test
  void anorderForNothingIsRefused() {
    assertThatThrownBy(
            () -> Order.place(new OrderId("order-1"), "customer-1", "sku-keyboard", 0, LONDON))
        .isInstanceOf(DomainException.class);
  }
}
