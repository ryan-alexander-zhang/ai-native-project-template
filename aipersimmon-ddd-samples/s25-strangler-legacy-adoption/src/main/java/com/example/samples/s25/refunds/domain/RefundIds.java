package com.example.samples.s25.refunds.domain;

/**
 * Reserving an id before the insert, and the reason it has to exist at all.
 *
 * <p>The library's aggregate repository builds its insert from {@code toRow}, and it refuses to continue if the row
 * comes back without a primary key: {@code "came back from toRow with no primary key value, so an update would
 * match every row of the table"}. That guard is right, and it means <strong>an aggregate cannot be inserted into a
 * table whose identity the database assigns.</strong> A legacy {@code BIGSERIAL} is exactly that table.
 *
 * <p>So the id is taken from the sequence first, in the same transaction, and the insert supplies it. Which is a
 * two-line port and one round trip, and is the honest answer for a legacy table — {@code nextval} on the table's own
 * sequence keeps the numbering single-sourced, so the old path's inserts and the new path's inserts cannot collide.
 *
 * <p>{@code AutoIncrementIdentityTest} measures both halves: what the library does when the id is absent, and that
 * reserving it first works. It is a genuine friction between the library's write path and a schema it did not
 * design; see {@code docs/issue/issue-00171}.
 */
public interface RefundIds {

  /** The next id from the legacy table's own sequence. */
  RefundId reserve();
}
