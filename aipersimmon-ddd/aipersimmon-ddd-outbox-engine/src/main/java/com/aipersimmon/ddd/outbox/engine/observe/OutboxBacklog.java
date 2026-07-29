package com.aipersimmon.ddd.outbox.engine.observe;

import com.aipersimmon.ddd.outbox.engine.store.OutboxStore;
import com.aipersimmon.ddd.outbox.engine.store.PendingBacklog;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Pull-based backlog reads over the outbox table: how many messages are still waiting to go out,
 * and how long the oldest has been waiting.
 *
 * <p>These are the two classic outbox alerts, and until now getting either meant writing SQL
 * against a table the application does not own. Depth alone is ambiguous — a thousand rows seconds
 * old is a busy system — so the age is what distinguishes load from a stalled relay.
 *
 * <p>Read-only and framework-free; the starter's meter binder samples it on demand. Ages are
 * computed against a {@link Clock} here rather than stored, so a sample cannot go stale between
 * being taken and being read.
 */
public final class OutboxBacklog {

  private final OutboxStore store;
  private final Clock clock;
  private final int maxAttempts;

  public OutboxBacklog(OutboxStore store, Clock clock, int maxAttempts) {
    this.store = store;
    this.clock = clock;
    this.maxAttempts = maxAttempts;
  }

  /**
   * One read of both signals.
   *
   * @return the depth, and the age of the oldest waiting message ({@link Duration#ZERO} when the
   *     outbox is empty — "nothing is late" rather than an absent reading, so a gauge has a value)
   */
  public Snapshot snapshot() {
    Instant now = clock.instant();
    PendingBacklog backlog = store.pendingBacklog(maxAttempts);
    return new Snapshot(backlog.rows(), age(backlog.oldestCreatedAt(), now));
  }

  private static Duration age(Instant oldest, Instant now) {
    if (oldest == null) {
      return Duration.ZERO;
    }
    Duration age = Duration.between(oldest, now);
    // A row written by a node whose clock runs ahead would otherwise read as negative age.
    return age.isNegative() ? Duration.ZERO : age;
  }

  /**
   * An immutable point-in-time read.
   *
   * @param pending unsent messages the relay still intends to deliver
   * @param oldestPendingAge how long the oldest of them has been waiting since it was written
   */
  public record Snapshot(long pending, Duration oldestPendingAge) {}
}
