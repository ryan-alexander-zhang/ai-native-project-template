package com.aipersimmon.ddd.outbox.engine.relay;

import com.aipersimmon.ddd.outbox.engine.store.OutboxLease;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Mints the lease each claim runs under: one owner for the life of the process, a fresh token per
 * claim, and a fixed duration.
 *
 * <p>The duration is the relay's recovery bound. An instance killed mid-poll releases nothing, so
 * the rows it had claimed stay unavailable exactly this long and then become claimable by anyone.
 * Shorter means faster recovery; longer means more room for a slow poll to finish before its rows
 * can be picked up twice. It is the only thing the length of the lease governs — how long a poll
 * may run is bounded separately, by the batch size and the relay's own time budget.
 */
public final class RelayLeases {

  private final String owner;
  private final Supplier<String> tokens;
  private final Duration duration;

  public RelayLeases(String owner, Supplier<String> tokens, Duration duration) {
    if (duration == null || duration.isZero() || duration.isNegative()) {
      throw new IllegalArgumentException("lease duration must be positive, got " + duration);
    }
    this.owner = owner;
    this.tokens = tokens;
    this.duration = duration;
  }

  /** Leases owned by a process-scoped generated id, for when no stable worker id is configured. */
  public static RelayLeases forThisProcess(Duration duration) {
    return new RelayLeases(
        "outbox-relay-" + UUID.randomUUID(), () -> UUID.randomUUID().toString(), duration);
  }

  /** Leases owned by the given worker id, with generated tokens. */
  public static RelayLeases ownedBy(String owner, Duration duration) {
    return new RelayLeases(owner, () -> UUID.randomUUID().toString(), duration);
  }

  /** A fresh lease starting now. The token is never reused, so a claim reads back unambiguously. */
  public OutboxLease next(Instant now) {
    return new OutboxLease(owner, tokens.get(), now.plus(duration));
  }

  public Duration duration() {
    return duration;
  }
}
