package com.example.samples.s25;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * When is the migration finished, and when may the old path be deleted?
 *
 * <p>The catalogue's last question, and the useful thing a sample can contribute is that it has a <strong>computable</strong>
 * answer rather than a meeting. Four conditions, each read out of the code or the schema:
 *
 * <ol>
 *   <li><strong>no legacy method writes the table.</strong> The same computation that chose the first aggregate
 *       ({@code LegacyFanInTest}) counts writers; the criterion is that the count reaches zero;
 *   <li><strong>nothing reaches the legacy methods.</strong> The route is {@code NEW_ONLY} and the bodies are gone;
 *   <li><strong>the foreign key into the un-extracted table is gone.</strong> Until it is, the table cannot move to another
 *       database, so "extracted" is only true within one deployment;
 *   <li><strong>the columns the aggregate does not own are dealt with.</strong> {@code created_at}/{@code updated_at} are
 *       still the monolith's, and one legacy method does not maintain them — so a reader that trusts them is trusting
 *       something nobody owns.
 * </ol>
 *
 * <p>This sample is deliberately <strong>not finished</strong>, and the assertions below say so with numbers. A sample that
 * showed only the finished state would skip the part that takes eighteen months.
 */
class DoneCriterionTest extends StranglerTestBase {

  /**
   * Condition 1: writers per table, and the strangled table is not at zero.
   *
   * <p>Two legacy methods still write {@code legacy_refunds} — they are unreachable through the seam but they compile, and
   * "unreachable" is a property of one config value. The criterion is deliberately about the code rather than about the
   * configuration, because a config value can be changed by somebody who has not read this file.
   */
  @Test
  void thestrangledTableStillHasLegacyWritersSoTheMigrationIsNotDone() {
    Map<String, Integer> writers = LegacyFanInTest.writersPerTable();
    assertThat(writers.get("legacy_refunds"))
        .as("still writable from the monolith; the criterion is that this reaches zero")
        .isPositive();
  }

  /**
   * Condition 3: the foreign key is still there, so the table cannot leave this database.
   *
   * <p>Read out of the catalogue rather than out of anybody's memory. It is the condition most often forgotten, because
   * within one deployment nothing about it hurts — and it is the one that makes "we extracted the aggregate" and "we can
   * deploy it separately" two different statements.
   */
  @Test
  void theforeignKeyIntoTheMonolithIsStillThere() {
    Long constraints =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.table_constraints tc"
                + " JOIN information_schema.constraint_column_usage ccu"
                + "   ON tc.constraint_name = ccu.constraint_name"
                + " WHERE tc.table_name = 'legacy_refunds' AND tc.constraint_type = 'FOREIGN KEY'"
                + "   AND ccu.table_name = 'legacy_orders'",
            Long.class);
    assertThat(constraints)
        .as("legacy_refunds still references legacy_orders, so it cannot move databases")
        .isEqualTo(1);
  }

  /**
   * Condition 4: the columns nobody owns are still there, and still unmaintained by one path.
   *
   * <p>Asserted because it is the trap in "the aggregate owns the table now": it owns seven of the ten columns. A reader
   * that treats {@code updated_at} as a change marker is trusting a column that {@code addNote} does not touch — measured
   * in {@code DoubleWriteTest} — and the fix is not in the new context.
   */
  @Test
  void thecolumnsTheAggregateDoesNotOwnAreStillOnTheTable() {
    Long unowned =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.columns"
                + " WHERE table_name = 'legacy_refunds'"
                + "   AND column_name IN ('created_at', 'updated_at')",
            Long.class);
    assertThat(unowned).isEqualTo(2);
  }

  /**
   * What <em>is</em> already true, so the criterion is not only a list of debts.
   *
   * <p>Three of the migration's goals are met and measurable: every row has an outward identity whichever path made it, the
   * rules are in one place and refuse, and the version column is being advanced by the writer that participates. That is
   * what one extraction buys — and the list above is what it does not.
   */
  @Test
  void whatIsAlreadyTrueAndMeasurable() {
    long orderId = placeLegacyOrder(10_000);
    long refundId = entryPoint.raiseRefund(orderId, 2_500, "damaged");

    assertThat(refundRow(refundId).get("public_id")).as("an outward identity exists").isNotNull();
    assertThat(refundVersion(refundId)).as("and the version column is live").isEqualTo(1);
    assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.columns"
                + " WHERE table_name = 'legacy_refunds' AND column_name = 'version'",
            Long.class))
        .isEqualTo(1);
  }
}
