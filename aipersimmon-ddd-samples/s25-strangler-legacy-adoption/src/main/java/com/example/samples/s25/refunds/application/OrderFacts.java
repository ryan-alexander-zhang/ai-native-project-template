package com.example.samples.s25.refunds.application;

import java.util.Optional;

/**
 * What the new context needs to know about an order, in the new context's words.
 *
 * <p>The port half of the anti-corruption layer, and the reason it is declared <em>here</em> rather than in the ACL
 * package: the new context states what it needs, and the ACL's job is to satisfy that shape from whatever the monolith
 * happens to have. Declared the other way round, the legacy vocabulary decides the interface — and an ACL that
 * publishes the legacy's shape is not an ACL, it is a forwarding address.
 *
 * <p>Note what it does not return: not {@code LegacyOrderRecord}, not the status string, not a total with no currency.
 * Two facts, named in the terms the aggregate reasons in. Every legacy concept stops at the implementation, and
 * {@code ArchitectureTest} pins that.
 */
public interface OrderFacts {

  /** The facts about one order, or empty when the monolith has never heard of it. */
  Optional<Snapshot> of(long orderId);

  /**
   * @param cancelled translated from a status string with four values that is not quite an enum
   * @param totalCents the order's total
   */
  record Snapshot(boolean cancelled, long totalCents) {}
}
