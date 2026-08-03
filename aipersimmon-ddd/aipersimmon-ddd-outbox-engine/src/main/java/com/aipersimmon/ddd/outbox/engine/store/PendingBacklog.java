package com.aipersimmon.ddd.outbox.engine.store;

import java.time.Instant;

/**
 * How much live work the outbox is holding, read in one pass.
 *
 * <p>Both signals come from the same scan because they answer the same question from two sides —
 * <em>how much</em> is waiting and <em>how long</em> the oldest has waited — and an operator needs
 * them together: a deep backlog that is seconds old is a busy system, a shallow one that is an hour
 * old is a broken relay.
 *
 * @param rows unsent rows that have not exhausted their attempts, i.e. work the relay still intends
 *     to deliver. A row given up on has left for the dead-letter table and is not counted.
 * @param oldestCreatedAt when the oldest of those rows was written, or {@code null} when there are
 *     none. The age is computed against the clock by the caller, not stored here, so a snapshot
 *     does not go stale in the reading.
 * @param givenUp unsent rows at or beyond {@code maxAttempts} — rows the relay will neither claim
 *     nor dead-letter. Normally zero, because exhausting the last attempt dead-letters the row in
 *     the same act; it goes nonzero when {@code max-attempts} was <em>lowered</em> across a
 *     restart, stranding rows whose attempt count the old limit permitted. Without this count those
 *     rows are invisible: not pending, not dead-lettered, findable only by hand-written SQL.
 */
public record PendingBacklog(long rows, Instant oldestCreatedAt, long givenUp) {

  /** Nothing is waiting. */
  public static final PendingBacklog EMPTY = new PendingBacklog(0, null, 0);
}
