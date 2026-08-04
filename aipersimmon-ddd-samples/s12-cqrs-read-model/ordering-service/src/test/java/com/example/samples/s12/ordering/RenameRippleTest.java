package com.example.samples.s12.ordering;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.samples.s12.ordering.application.RebuildOrderList;
import com.example.samples.s12.ordering.application.RecordProductName;
import org.junit.jupiter.api.Test;

/**
 * What happens to the read model when another context renames something — driven by the command rather than
 * by a Kafka record, so the ripple can be measured without a broker in the way. The transport's own leg has
 * its own test.
 */
class RenameRippleTest extends ReadModelTestBase {

  @Test
  void thelistShowsTheNewNameAndTheInvoiceStillShowsTheOld() {
    String orderId = placeOrder(KEYBOARD, MOUSE);
    assertThat(summaryOf(orderId)).isEqualTo("Mechanical Keyboard, Wireless Mouse");

    commandBus.send(new RecordProductName(KEYBOARD, "Mechanical Keyboard Mk II"));

    // The sample's central pair, in one test. Same value, two opposite requirements, two mechanisms:
    // the list page must follow the catalogue, and the order's own record of what was bought must not.
    assertThat(summaryOf(orderId)).isEqualTo("Mechanical Keyboard Mk II, Wireless Mouse");
    assertThat(frozenNamesOf(orderId)).containsExactly("Mechanical Keyboard", "Wireless Mouse");
  }

  @Test
  void arenameRecomputesEveryOrderThatEverContainedTheProduct() {
    placeOrder(KEYBOARD);
    placeOrder(KEYBOARD, MOUSE);
    placeOrder(MOUSE);

    int recomputed = commandBus.send(new RecordProductName(KEYBOARD, "Keyboard Pro"));

    // The number is the point, and it is why the handler returns it. One rename of one product rewrote two
    // list rows; on a real catalogue that number is "every order ever placed for a popular item", unbounded
    // and growing. This is the bill for denormalising another context's data, and it lands on the write path
    // of an event whose rate you do not control.
    assertThat(recomputed).isEqualTo(2);
  }

  @Test
  void arenameForAProductNobodyOrderedCostsNothingButStillLandsInTheReplica() {
    placeOrder(KEYBOARD);

    int recomputed = commandBus.send(new RecordProductName(MONITOR, "27-inch Monitor"));

    assertThat(recomputed).isZero();
    // The replica is updated anyway, so the next order for that product gets the real name. A replica that
    // only recorded names it currently needed would be a cache, and would have to ask the catalogue again.
    assertThat(
            jdbc.queryForObject(
                "SELECT name FROM s12_product_name WHERE sku = ?", String.class, MONITOR))
        .isEqualTo("27-inch Monitor");
  }

  @Test
  void anameLearnedAfterTheOrderCorrectsTheListButNotTheInvoice() {
    String orderId = placeOrder(MONITOR);
    assertThat(summaryOf(orderId)).isEqualTo(MONITOR);

    commandBus.send(new RecordProductName(MONITOR, "27-inch Monitor"));

    // Self-healing, and asymmetric on purpose: the display corrects itself, the frozen record does not.
    // The customer was shown a sku when they bought it, and rewriting that would be a lie about history.
    assertThat(summaryOf(orderId)).isEqualTo("27-inch Monitor");
    assertThat(frozenNamesOf(orderId)).containsExactly(MONITOR);
  }

  @Test
  void arebuildAfterARenameKeepsTheNewName() {
    String orderId = placeOrder(KEYBOARD);
    commandBus.send(new RecordProductName(KEYBOARD, "Keyboard Pro"));

    jdbc.update("DELETE FROM s12_order_list");
    commandBus.send(new RebuildOrderList());

    // The assertion that justifies the replica's existence. Had the rename listener written the new name
    // straight into the projection row and kept no replica, this rebuild would have produced "Mechanical
    // Keyboard" — the value the write model froze — and the only way back would be re-asking the catalogue
    // or replaying its whole event history from the broker's retention. A projection is rebuildable exactly
    // when every input it needs is a table you own.
    assertThat(summaryOf(orderId)).isEqualTo("Keyboard Pro");
  }
}
