package com.aipersimmon.ddd.processmanager.jdbc.lease;

import com.aipersimmon.ddd.processmanager.engine.lease.ProcessClaimSql;
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
   * @return the claimed effect ids, longest-due first. Per-instance order needs no defending here:
   *     the head-of-line predicate admits at most one effect per instance, so a batch never holds
   *     two of the same instance's effects to put in the wrong order.
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
   * @return the claimed deadline ids, earliest {@code due_at} first, ties broken by id
   */
  List<String> claimDueDeadlines(
      JdbcTemplate jdbc,
      Instant now,
      int limit,
      WorkerId owner,
      String leaseToken,
      Instant leaseUntil);

  /**
   * The claimable-and-unblocked candidate query, in JDBC placeholder form. Two positional
   * parameters, both {@code now}. See {@link ProcessClaimSql#EFFECT_CANDIDATE} for what it says and
   * why it is spelled the way it is.
   */
  String CANDIDATE_SQL = ProcessClaimSql.positional(ProcessClaimSql.EFFECT_CANDIDATE);

  /**
   * The due-deadline candidate query, in JDBC placeholder form. Two positional {@code now} params.
   * See {@link ProcessClaimSql#DEADLINE_CANDIDATE}.
   */
  String DEADLINE_CANDIDATE_SQL = ProcessClaimSql.positional(ProcessClaimSql.DEADLINE_CANDIDATE);
}
