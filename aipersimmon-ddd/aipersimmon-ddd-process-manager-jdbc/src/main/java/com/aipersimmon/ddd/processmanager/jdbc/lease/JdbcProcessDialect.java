package com.aipersimmon.ddd.processmanager.jdbc.lease;

import com.aipersimmon.ddd.processmanager.engine.lease.WorkerId;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Encapsulates the database-specific way to atomically claim due effects for delivery. The
 * claimable set and per-instance ordering are the same across databases; only the concurrency
 * mechanism differs — {@code FOR UPDATE SKIP LOCKED} on PostgreSQL/MySQL, an atomic conditional
 * {@code UPDATE} where that is unavailable.
 *
 * <p>An effect is claimable when it is due ({@code PENDING} past its next attempt, or {@code
 * IN_FLIGHT} past a stale lease) and not blocked by an earlier-ordered, not-yet-delivered effect on
 * the same instance — so dispatch is serial per instance. A claim marks the row {@code IN_FLIGHT}
 * and writes the lease; {@code attempts} is bumped only on a failed delivery (by the store's
 * retry/dead transition), never by a claim, so a lease-expiry reclaim does not consume the retry
 * budget.
 */
public interface JdbcProcessDialect {

  /** A short id for logging and startup validation (for example {@code "postgresql"}). */
  String id();

  /**
   * Claim up to {@code limit} due, unblocked effects, marking each {@code IN_FLIGHT} with the given
   * lease. Must run inside a transaction.
   *
   * @return the claimed effect ids, in dispatch order (per-instance {@code seq})
   */
  List<String> claimDueEffects(
      JdbcTemplate jdbc,
      Instant now,
      int limit,
      WorkerId owner,
      String leaseToken,
      Instant leaseUntil);

  /**
   * Claim up to {@code limit} due deadlines, marking each {@code IN_FLIGHT} with the lease. Unlike
   * effects, deadlines need no per-instance head-of-line ordering: firing re-enters {@code handle},
   * which takes the instance lock, so concurrent fires on one instance serialize there. Must run
   * inside a transaction.
   *
   * @return the claimed deadline ids, earliest {@code due_at} first
   */
  List<String> claimDueDeadlines(
      JdbcTemplate jdbc,
      Instant now,
      int limit,
      WorkerId owner,
      String leaseToken,
      Instant leaseUntil);

  /**
   * The claimable-and-unblocked candidate query, shared by all dialects. Two positional parameters,
   * both {@code now}: the {@code PENDING} due bound and the stale-lease bound. Ordered by the
   * per-instance monotonic {@code seq} so the head comes first, and an effect is blocked by any
   * earlier-{@code seq}, not-yet-delivered effect on the same instance — a durable key, not the
   * wall-clock {@code created_at}.
   */
  String CANDIDATE_SQL =
      """
            SELECT e.effect_id FROM aipersimmon_process_effect e
            WHERE ((e.status = 'PENDING' AND e.next_attempt_at <= ?)
                   OR (e.status = 'IN_FLIGHT' AND e.lease_until <= ?))
              AND NOT EXISTS (
                  SELECT 1 FROM aipersimmon_process_effect b
                  WHERE b.instance_id = e.instance_id
                    AND b.status <> 'DELIVERED'
                    AND b.seq < e.seq)
            ORDER BY e.seq""";

  /**
   * The due-deadline candidate query, shared by all dialects. Two positional {@code now} params.
   * Only deadlines of active instances are claimable — a suspended or ended instance's deadlines
   * are skipped and become candidates again after it resumes.
   */
  String DEADLINE_CANDIDATE_SQL =
      """
            SELECT d.deadline_id FROM aipersimmon_process_deadline d
            JOIN aipersimmon_process_instance i ON i.instance_id = d.instance_id
            WHERE ((d.status = 'PENDING' AND d.next_attempt_at <= ?)
                   OR (d.status = 'IN_FLIGHT' AND d.lease_until <= ?))
              AND i.lifecycle IN ('RUNNING', 'COMPENSATING')
            ORDER BY d.due_at""";
}
