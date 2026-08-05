package com.example.samples.s25;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.samples.s25.refunds.domain.Refund;
import com.example.samples.s25.refunds.domain.RefundId;
import org.junit.jupiter.api.Test;

/**
 * The double-write period, and whether the outbox can feed the new context through it.
 *
 * <p>Two answers, and the second one makes the first one moot:
 *
 * <ol>
 *   <li><strong>Yes, the outbox works</strong> — for writes that come through the library's transaction. The row lands in
 *       the same commit as the aggregate, which is S8's guarantee and it holds just as well over a table the library did
 *       not design;
 *   <li><strong>and the legacy path cannot use it at all.</strong> No {@code IntegrationEvents}, no participation in the
 *       library's transaction, nothing to write a row with. So during a period when both paths write, the feed covers
 *       half the changes — and the half it covers is the half that did not need it.
 * </ol>
 *
 * <p>Which is why {@code LegacyRefundEntryPoint} has no {@code BOTH} route. The arrangement everybody reaches for during a
 * migration — write both, reconcile later — has no mechanism behind it here, and the tests below measure the shape of the
 * gap rather than describing it.
 */
class DoubleWriteTest extends StranglerTestBase {

  /** A write through the new path leaves a fact behind, in the same commit. */
  @Test
  void awriteThroughTheNewPathPublishesAFact() {
    long orderId = placeLegacyOrder(10_000);
    long refundId = entryPoint.raiseRefund(orderId, 2_500, "damaged");

    assertThat(outboxRowCount()).isEqualTo(1);
    assertThat(refundRow(refundId)).containsEntry("state", "OPEN");
  }

  /**
   * <strong>A write through the legacy path publishes nothing, and nothing can make it.</strong>
   *
   * <p>This is the whole answer to "can the outbox feed the new context during the double-write period". It can feed it
   * about the writes it sees, and it sees exactly the writes that went through the library. A monolith's {@code INSERT}
   * is invisible to it — not because of a missing configuration, but because the outbox row is written by the code that
   * publishes, and the monolith does not publish.
   */
  @Test
  void awriteThroughTheLegacyPathPublishesNothing() {
    long orderId = placeLegacyOrder(10_000);
    long refundId = legacy.raiseRefund(orderId, 2_500, "damaged");

    assertThat(refundRow(refundId)).as("the row is there").containsEntry("state", "OPEN");
    assertThat(outboxRowCount()).as("and nothing was announced").isZero();
  }

  /**
   * So a reader of the feed and a reader of the table disagree — and cannot tell why.
   *
   * <p>Three refunds, two paths, and the counts differ. What makes this worse than a lag is that the difference is not a
   * lag: waiting does not fix it, because nothing is on its way. A consumer built on the feed has no way to distinguish
   * "not yet published" from "will never be published", which is the property that makes a reconciler on top of a
   * double-write arrangement unable to converge without reading the table directly — at which point the feed is
   * decoration.
   */
  @Test
  void thefeedAndTheTableDisagreeAndWaitingDoesNotHelp() {
    long orderId = placeLegacyOrder(100_000);

    long viaNew = entryPoint.raiseRefund(orderId, 100, "new path");
    close(viaNew);
    long viaLegacy = legacy.raiseRefund(orderId, 200, "old path");
    close(viaLegacy);
    long viaNewAgain = entryPoint.raiseRefund(orderId, 300, "new path again");

    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_refunds", Long.class))
        .as("three refunds exist")
        .isEqualTo(3);
    assertThat(outboxRowCount()).as("and two were announced").isEqualTo(2);
    assertThat(viaLegacy).isNotEqualTo(viaNew).isNotEqualTo(viaNewAgain);
  }

  /**
   * And the fact that both paths write the same table is itself the problem, not the feed.
   *
   * <p>The new context can read a row the legacy path wrote — the table is the same table, so of course it can. What it
   * cannot do is know when the row changed, because {@code updated_at} is maintained by hand in the monolith and one
   * method forgets. So even a reconciler polling the table has no reliable high-water mark, and that is a property of the
   * legacy schema rather than of anything the library does or does not offer.
   */
  @Test
  void thenewContextCanReadALegacyRowButNotTellWhenItChanged() {
    long orderId = placeLegacyOrder(10_000);
    long refundId = legacy.raiseRefund(orderId, 2_500, "damaged");

    Refund read = refunds.find(new RefundId(refundId)).orElseThrow();
    assertThat(read.amountCents()).as("the row is readable through the aggregate").isEqualTo(2_500);
    assertThat(read.publicId()).as("and it has an outward identity, from the column default").isNotNull();

    // The monolith's own timestamp discipline, measured: addNote does not touch updated_at.
    java.time.Instant before =
        jdbc.queryForObject(
            "SELECT updated_at FROM legacy_orders WHERE id = ?", java.time.Instant.class, orderId);
    legacy.addNote(orderId, "customer called");
    java.time.Instant after =
        jdbc.queryForObject(
            "SELECT updated_at FROM legacy_orders WHERE id = ?", java.time.Instant.class, orderId);
    assertThat(after)
        .as("the row changed and its timestamp did not, so no poller can use it as a watermark")
        .isEqualTo(before);
    assertThat(jdbc.queryForObject(
            "SELECT notes FROM legacy_orders WHERE id = ?", String.class, orderId))
        .isEqualTo("customer called");
  }

  /** Close a refund so the "one open refund per order" rule does not get in the way of the arithmetic. */
  private void close(long refundId) {
    jdbc.update("UPDATE legacy_refunds SET state = 'REJECTED' WHERE id = ?", refundId);
  }
}
