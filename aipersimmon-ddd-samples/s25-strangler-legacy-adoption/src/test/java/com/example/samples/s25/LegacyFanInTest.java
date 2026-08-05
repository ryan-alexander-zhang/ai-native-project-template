package com.example.samples.s25;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Which aggregate first? The one with the fewest writers and the most rules — computed, not argued.
 *
 * <p>This is the catalogue's first question and the one usually answered by whoever is loudest. It has a mechanical
 * answer, and the ingredients are already in the monolith:
 *
 * <ul>
 *   <li><strong>writers per table</strong> — how many methods issue an {@code INSERT}/{@code UPDATE}/{@code DELETE}
 *       against it. This is the cost of extraction: every writer is a call site that has to be routed or left behind, and
 *       a table with four of them cannot be given a single owner in one change;
 *   <li><strong>rules per table</strong> — how much business logic is attached to it. This is the benefit: a table with no
 *       rules gains nothing from becoming an aggregate, however tidy the result looks.
 * </ul>
 *
 * <p>Read out of the compiled monolith so it stays true as the code changes, and it does double duty: the same computation
 * that picks the first aggregate is what later says the migration is finished — {@code DoneCriterionTest} asserts the
 * writer count for the strangled table reaches zero.
 *
 * <p>It is a regex over annotation-free JDBC strings, which cannot work in general — the monolith's SQL is in string
 * literals inside method bodies, not in annotations, so reflection cannot see it. So this test reads the <em>source</em>.
 * Crude, and it runs; see the note in {@code TableOwnershipTest} in S24 for the same trade.
 */
class LegacyFanInTest {

  private static final Pattern WRITE =
      Pattern.compile(
          "(insert\\s+into|update|delete\\s+from)\\s+(legacy_[a-z_]+)", Pattern.CASE_INSENSITIVE);

  /** Business rules look like this in a monolith: a throw next to some SQL. */
  private static final Pattern RULE = Pattern.compile("throw new IllegalStateException");

  private static final java.nio.file.Path MONOLITH =
      java.nio.file.Path.of("src/main/java/com/example/samples/s25/legacy/LegacyOrderService.java");

  /**
   * The measurement that chose this sample's first aggregate.
   *
   * <p>{@code legacy_orders} has the most writers and would be the worst first pick — every one of them is a call site to
   * route, and until all four are routed the table has two owners. {@code legacy_refunds} has the fewest and carries the
   * rules, so it goes first. That ordering generalises: <strong>strangle from the leaves inward</strong>, because a leaf is
   * the only thing that can have a single owner after one change.
   */
  @Test
  void therefundsTableHasTheFewestWritersWhichIsWhyItWentFirst() {
    Map<String, Integer> writers = writersPerTable();

    assertThat(writers)
        .as("the monolith's writers, per table")
        .containsKeys("legacy_orders", "legacy_order_items", "legacy_refunds");
    assertThat(writers.get("legacy_refunds"))
        .as("fewest writers: the cheapest table to give a single owner")
        .isLessThan(writers.get("legacy_orders"));
    assertThat(writers.get("legacy_order_items"))
        .as("and order items sit in the middle, which is where they belong in the queue")
        .isLessThan(writers.get("legacy_orders"));
  }

  /** And it carries rules, which is the other half — a table with no rules gains nothing from being an aggregate. */
  @Test
  void therefundMethodsAreWhereTheMonolithsRulesActuallyAre() {
    String source = source();
    int rules = count(RULE.matcher(source));
    assertThat(rules).as("throws in the monolith, all of them in the refund path").isPositive();

    int inRefundMethod = count(RULE.matcher(methodBody(source, "raiseRefund")));
    assertThat(inRefundMethod)
        .as("every rule the monolith expresses at all is in the method being extracted")
        .isEqualTo(rules);
  }

  /**
   * The criterion is a pair, and neither half is sufficient — worth asserting so the procedure is not remembered as
   * "pick the small one".
   *
   * <p>{@code legacy_order_items} has few writers and no rules: extracting it would produce a tidy aggregate that refuses
   * nothing, which is work with no payoff. {@code legacy_orders} has all the fan-in and would be the most valuable
   * eventually — which is exactly why it is not first.
   */
  @Test
  void neitherHalfOfTheCriterionIsSufficientOnItsOwn() {
    String source = source();
    assertThat(count(RULE.matcher(methodBody(source, "addItem"))))
        .as("order items: few writers, no rules — nothing to gain")
        .isZero();
    assertThat(writersPerTable().get("legacy_orders"))
        .as("orders: the most rules eventually, and the most call sites now")
        .isGreaterThan(2);
  }

  static Map<String, Integer> writersPerTable() {
    Map<String, Integer> writers = new LinkedHashMap<>();
    Matcher matcher = WRITE.matcher(source());
    while (matcher.find()) {
      writers.merge(matcher.group(2).toLowerCase(Locale.ROOT), 1, Integer::sum);
    }
    return writers;
  }

  static String source() {
    try {
      return java.nio.file.Files.readString(MONOLITH);
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException("cannot read the monolith at " + MONOLITH, e);
    }
  }

  /** From the method's signature to the next one — crude and sufficient for a one-class monolith. */
  private static String methodBody(String source, String methodName) {
    int start = source.indexOf(" " + methodName + "(");
    if (start < 0) {
      throw new AssertionError("no method " + methodName + " in the monolith any more");
    }
    int next = source.indexOf("\n  public ", start + 1);
    return next < 0 ? source.substring(start) : source.substring(start, next);
  }

  private static int count(Matcher matcher) {
    int found = 0;
    while (matcher.find()) {
      found++;
    }
    return found;
  }
}
