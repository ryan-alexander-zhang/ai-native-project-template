package com.aipersimmon.ddd.processmanager.engine.lease;

import java.time.Instant;
import java.util.List;

/**
 * The database-specific way to atomically claim due effects and deadlines for a worker. The
 * claimable set and per-instance ordering are the same across databases; only the concurrency
 * mechanism differs — {@code FOR UPDATE SKIP LOCKED} where available, an atomic conditional {@code
 * UPDATE} where it is not. The worker identity and the database handle are captured by the
 * implementation, so the engine's relay and deadline worker depend only on this port.
 *
 * <p>An effect is claimable when it is due ({@code PENDING} past its next attempt, or {@code
 * IN_FLIGHT} past a stale lease) and not blocked by an earlier-ordered, not-yet-delivered effect on
 * the same instance — so dispatch is serial per instance. A claim marks the row {@code IN_FLIGHT}
 * and writes the lease; {@code attempts} is bumped only on a failed delivery, never by a claim, so
 * a lease-expiry reclaim does not consume the retry budget. Each method must run inside a
 * transaction (the caller opens one through the {@code ProcessUnitOfWork}).
 */
public interface ProcessClaimStrategy {

  /** A short id for logging and startup validation (for example {@code "postgresql"}). */
  String id();

  /**
   * Claim up to {@code limit} due, unblocked effects, marking each {@code IN_FLIGHT} with the given
   * lease.
   *
   * @return the claimed effect ids, in dispatch order (per-instance {@code seq})
   */
  List<String> claimDueEffects(Instant now, int limit, String leaseToken, Instant leaseUntil);

  /**
   * Claim up to {@code limit} due deadlines of active instances, marking each {@code IN_FLIGHT}
   * with the lease.
   *
   * @return the claimed deadline ids, earliest {@code due_at} first
   */
  List<String> claimDueDeadlines(Instant now, int limit, String leaseToken, Instant leaseUntil);
}
