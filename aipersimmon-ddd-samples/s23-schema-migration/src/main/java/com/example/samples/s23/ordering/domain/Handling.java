package com.example.samples.s23.ordering.domain;

import java.util.Set;

/**
 * How an order is handled, and the rule that decides it.
 *
 * <p>The rule is here — one place, in the domain — and that is the whole point of the column V4 adds. New
 * orders are decided by it at placement; the rows that predate the column are decided by it during the
 * backfill. A {@code CASE WHEN quantity >= 10 OR city IN (...)} inside a migration would have been the
 * second copy, in a language the domain cannot test, maintained by whoever last touched the database — and
 * it would have drifted the first time the carrier added an island.
 */
public enum Handling {
  STANDARD,
  EXPEDITED;

  /**
   * Cities the carrier treats as remote. Domain knowledge, and the reason the rule cannot be expressed as
   * arithmetic on the row: it is a list somebody maintains.
   */
  private static final Set<String> REMOTE = Set.of("Shetland", "Orkney", "Isle of Skye");

  /** Large orders and remote destinations are handled differently. */
  public static Handling decide(int quantity, ShipTo shipTo) {
    return quantity >= 10 || REMOTE.contains(shipTo.city()) ? EXPEDITED : STANDARD;
  }
}
