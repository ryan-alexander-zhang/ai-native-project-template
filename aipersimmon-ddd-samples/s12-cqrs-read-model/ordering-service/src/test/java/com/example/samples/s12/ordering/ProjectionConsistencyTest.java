package com.example.samples.s12.ordering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.samples.s12.ordering.application.OrderListItem;
import com.example.samples.s12.ordering.application.PayOrder;
import com.example.samples.s12.ordering.application.PlaceOrder;
import com.example.samples.s12.ordering.application.RebuildOrderList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** What the customer sees, and when. */
class ProjectionConsistencyTest extends ReadModelTestBase {

  @Test
  void thelistShowsAnOrderTheInstantItIsPlaced() {
    String orderId = placeOrder(KEYBOARD, MOUSE);

    // No awaiting, no retry, no eventual anything: read-your-own-writes holds. Note what actually buys it —
    // the projection being maintained in process on the same thread, not the transaction phase. Switching to
    // AFTER_COMMIT leaves this test green, which is why the claim about phases is made by
    // apoisonedProjectionTakesTheWriteDownWithIt instead of by this one.
    List<OrderListItem> rows = list();

    assertThat(rows).hasSize(1);
    assertThat(rows.getFirst().orderId()).isEqualTo(orderId);
    assertThat(rows.getFirst().status()).isEqualTo("PLACED");
    assertThat(rows.getFirst().displaySummary()).isEqualTo("Mechanical Keyboard, Wireless Mouse");
    assertThat(rows.getFirst().totalMinor()).isEqualTo(3000);
  }

  @Test
  void payingAnOrderUpdatesItsRowWithNoLag() {
    String orderId = placeOrder(KEYBOARD);

    commandBus.send(new PayOrder(orderId));

    assertThat(list().getFirst().status()).isEqualTo("PAID");
    assertThat(list().getFirst().paidAt()).isNotNull();
  }

  @Test
  void anorderForAProductThisContextHasNeverHeardOfStillAppears() {
    // sku-monitor exists in the catalogue and not in this context's replica. The order must still be
    // placeable and still be listed: another context's silence cannot be allowed to stop this one working.
    String orderId = placeOrder(MONITOR);

    assertThat(list()).hasSize(1);
    assertThat(summaryOf(orderId)).isEqualTo(MONITOR);
    // And the frozen name is the same placeholder, because that is honestly what the customer was shown.
    assertThat(frozenNamesOf(orderId)).containsExactly(MONITOR);
  }

  @Test
  void everyRowCarriesTheMomentItWasComputed() {
    String orderId = placeOrder(KEYBOARD);

    // Returned to the caller on purpose: a read model is as-of-some-moment, and a client that is told which
    // moment can decide what to do about it. The difference between "the list is wrong" and "it is old".
    assertThat(list().getFirst().projectedAt()).isEqualTo(projectedAtOf(orderId));
  }

  /**
   * The one test that can tell which side of the transaction boundary the projection is on.
   *
   * <p>With {@code @EventListener} the projection's failure is the command's failure: no order, no row,
   * nothing half-done. With {@code @TransactionalEventListener(AFTER_COMMIT)} the same failure would leave the
   * order committed and its list row missing forever, and this test is the only one in the service that would
   * notice — every other assertion here passes under either phase.
   */
  @Test
  void apoisonedProjectionTakesTheWriteDownWithIt() {
    assertThatThrownBy(
            () ->
                commandBus.send(
                    new PlaceOrder(
                        "poison-customer", List.of(new PlaceOrder.Line(KEYBOARD, 1, 1500)))))
        .isInstanceOf(IllegalStateException.class);

    // Both sides rolled back. That is the trade being paid for: the read side is on the write side's critical
    // path, and in exchange there is never an order without its row.
    assertThat(orderRowCount()).isZero();
    assertThat(projectionRowCount()).isZero();
  }

  @Test
  void thewholeProjectionCanBeThrownAwayAndRebuilt() {
    String first = placeOrder(KEYBOARD);
    String second = placeOrder(MOUSE);
    commandBus.send(new PayOrder(second));
    String summaryBefore = summaryOf(first);

    // Simulating the reason a rebuild exists: a bug, a bad deployment, a schema change — the projection is
    // simply wrong, and the write model is not.
    jdbc.update("DELETE FROM s12_order_list");
    assertThat(list()).isEmpty();

    int projected = commandBus.send(new RebuildOrderList());

    assertThat(projected).isEqualTo(2);
    assertThat(projectionRowCount()).isEqualTo(2);
    assertThat(summaryOf(first)).isEqualTo(summaryBefore);
    // Including derived state that arrived through a later event: the payment is back too, because the
    // rebuild reads the write model rather than replaying events.
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM s12_order_list WHERE order_id = ?", String.class, second))
        .isEqualTo("PAID");
  }

  @Test
  void arebuildIsIdempotentDownToTheRow() {
    String orderId = placeOrder(KEYBOARD, MOUSE);
    String summaryBefore = summaryOf(orderId);

    commandBus.send(new RebuildOrderList());
    commandBus.send(new RebuildOrderList());

    // The whole-row upsert is what buys this. A projection maintained by deltas would have to be trusted
    // rather than checked, because there would be nothing to compare against.
    assertThat(projectionRowCount()).isEqualTo(1);
    assertThat(summaryOf(orderId)).isEqualTo(summaryBefore);
  }
}
