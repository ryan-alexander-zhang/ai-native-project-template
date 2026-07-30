package com.aipersimmon.ddd.processmanager.engine.lease;

/**
 * The claim predicates, shared by every storage backend.
 *
 * <p>The engine is storage-agnostic, but it already owns the relational schema — the migrations for
 * all three dialects ship from this module — and these two queries are part of that same contract:
 * which row a worker may take, and in what order. They lived as hand-maintained copies in the JDBC
 * and MyBatis backends until issue-00125, where both spellings turned out to be load-bearing rather
 * than cosmetic, and a copy could have been "tidied" back into its slower or unfair form without
 * anything objecting. One copy cannot drift from itself.
 *
 * <p>Written with MyBatis's {@code #{name}} placeholders so the mapper annotations can use these
 * constants directly (an annotation argument must be a compile-time constant); the JDBC backend
 * rewrites them to {@code ?} once, at class-init.
 */
public final class ProcessClaimSql {

  private ProcessClaimSql() {}

  /**
   * Claimable-and-unblocked effects. Both {@code #{now}} bindings are the same instant: the {@code
   * PENDING} due bound and the stale-lease bound. An effect is blocked by any earlier-{@code seq},
   * not-yet-delivered effect on the same instance — a durable key, not the wall-clock {@code
   * created_at} — so at most one effect per instance is ever a candidate and dispatch stays serial
   * per instance.
   *
   * <p>The blocking predicate is written as two ranges around {@code 'DELIVERED'} rather than the
   * obvious {@code <>}. They mean the same thing for a {@code NOT NULL} column, but {@code <>} is
   * not seekable on PostgreSQL: it made the anti-join re-read the instance's <em>entire delivered
   * history</em> on every poll, so the claim got linearly slower for exactly the long-running
   * instances a process manager exists to serve. Measured on PostgreSQL 18 with one instance
   * holding 200k delivered effects: 46,813 buffers and 87ms as {@code <>}, 66 buffers and 0.09ms as
   * two ranges — and the cost stops tracking history at all, tracking only open work. MySQL's
   * optimizer already performs this rewrite itself, which is why it was never slow there; writing
   * it out makes both databases take the plan MySQL was choosing anyway. Prefer this over
   * enumerating the non-delivered statuses: it cannot fall out of step with {@code EffectStatus}
   * when a status is added.
   *
   * <p>Ordered by <em>when the work came due</em>, not by {@code seq}. {@code seq} counts up per
   * instance, so ordering by it globally is not merely meaningless across instances but actively
   * unfair: a long-lived instance's seq is permanently higher than a fresh one's, so under a claim
   * limit it sorted last on every poll and was never claimed at all. Due time is fair, and it
   * self-corrects — work that has waited drifts to the front. {@code effect_id} breaks ties
   * decisively, so a tie plus a batch limit cannot starve one side of it.
   */
  public static final String EFFECT_CANDIDATE =
      "SELECT e.effect_id FROM aipersimmon_process_effect e"
          + " WHERE ((e.status = 'PENDING' AND e.next_attempt_at <= #{now})"
          + " OR (e.status = 'IN_FLIGHT' AND e.lease_until <= #{now}))"
          + " AND NOT EXISTS ("
          + " SELECT 1 FROM aipersimmon_process_effect b"
          + " WHERE b.instance_id = e.instance_id"
          + " AND (b.status < 'DELIVERED' OR b.status > 'DELIVERED')"
          + " AND b.seq < e.seq)"
          + " ORDER BY CASE WHEN e.status = 'PENDING' THEN e.next_attempt_at ELSE e.lease_until END,"
          + " e.effect_id";

  /**
   * Due deadlines of active instances. A suspended or ended instance's deadlines are skipped and
   * become candidates again after it resumes.
   *
   * <p>Unlike effects, deadlines need no per-instance head-of-line ordering: firing re-enters
   * {@code handle}, which takes the instance lock, so concurrent fires on one instance serialize
   * there.
   *
   * <p>{@code deadline_id} breaks ties in {@code due_at} decisively, matching the listing query in
   * the deadline store. Ties are the normal case here rather than an edge one — deadlines are set
   * from business durations, so a batch of them routinely falls due on the same second — and an
   * unstable order plus a claim limit can hand back the same subset every poll, leaving the rest
   * unfired indefinitely.
   */
  public static final String DEADLINE_CANDIDATE =
      "SELECT d.deadline_id FROM aipersimmon_process_deadline d"
          + " JOIN aipersimmon_process_instance i ON i.instance_id = d.instance_id"
          + " WHERE ((d.status = 'PENDING' AND d.next_attempt_at <= #{now})"
          + " OR (d.status = 'IN_FLIGHT' AND d.lease_until <= #{now}))"
          + " AND i.lifecycle IN ('RUNNING', 'COMPENSATING')"
          + " ORDER BY d.due_at, d.deadline_id";

  /** The same query with JDBC positional placeholders. */
  public static String positional(String sql) {
    return sql.replace("#{now}", "?");
  }
}
