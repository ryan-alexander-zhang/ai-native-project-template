package com.aipersimmon.ddd.web.store.jdbc;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Deletes web-store rows that have outlived their own declared lifetime.
 *
 * <p>All three stores already delete expired rows — but only for the one key being touched, and
 * only when it is touched again. An idempotency key or a nonce is used <em>once</em> by
 * construction, so for the overwhelming majority of rows that second visit never comes and the row
 * sits past {@code expires_at} forever. The two indexes this job scans were added by migration V3
 * for a retention job, with a comment saying so; the job is what was missing.
 *
 * <p>Unlike the process manager's retention, this defaults to <em>on</em>, and the difference is
 * not a change of heart. There the rows are business records and how long to keep them is the
 * deployer's decision. Here {@code expires_at} is the store's own statement that the row is dead —
 * the code deletes such rows already, whenever it happens to pass one. Finishing that job on a
 * schedule is not a policy.
 *
 * <p>No batch limit, because there is no way to write one that holds across all three dialects:
 * {@code DELETE ... LIMIT} is MySQL-only, and the process manager's select-then-delete-by-id shape
 * does not transfer to these composite primary keys. Each delete is driven by an index on the
 * column it filters, so a steady-state run removes one interval's worth of expiry and is small. The
 * one large run is the first one after this job is switched on over a database that has been
 * accumulating — a one-off, and the reason the interval defaults to something unhurried.
 *
 * <p>No lock either. Two instances sweeping at once issue overlapping deletes and the second
 * removes nothing; repeating a delete of an already-deleted row cannot corrupt anything, which is
 * the same reasoning the process manager's purge uses.
 */
public class JdbcWebStoreCleanup {

  private static final Logger log = LoggerFactory.getLogger(JdbcWebStoreCleanup.class);

  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final Duration rateLimitRetention;

  public JdbcWebStoreCleanup(JdbcTemplate jdbc, Clock clock, Duration rateLimitRetention) {
    this.jdbc = jdbc;
    this.clock = clock;
    this.rateLimitRetention = rateLimitRetention;
  }

  public void sweep() {
    Instant now = clock.instant();
    int idempotency =
        jdbc.update(
            "DELETE FROM aipersimmon_web_idempotency WHERE expires_at <= ?", Timestamp.from(now));
    int nonces =
        jdbc.update("DELETE FROM aipersimmon_web_nonce WHERE expires_at <= ?", Timestamp.from(now));
    int buckets = purgeRateLimitWindows(now);
    if (idempotency + nonces + buckets > 0) {
      log.debug(
          "Web-store cleanup removed {} idempotency, {} nonce and {} rate-limit rows",
          idempotency,
          nonces,
          buckets);
    }
  }

  /**
   * The rate-limit table has no {@code expires_at}: a counter is dead once its window has passed,
   * but the window length belongs to the policy the caller was checked against and the row does not
   * record it. So this one is driven by a configured retention, which must exceed the longest rate
   * limit window in use.
   *
   * <p>Setting it too short is survivable rather than dangerous: deleting a live counter resets
   * that bucket's quota, and a caller whose counter has gone missing is answered with this call's
   * own increment rather than an error. Hot buckets barely depend on this anyway — {@link
   * JdbcRateLimiter} sweeps its own bucket on every call — so what is left here is the cold ones,
   * the keys never seen again.
   */
  private int purgeRateLimitWindows(Instant now) {
    return jdbc.update(
        "DELETE FROM aipersimmon_web_rate_limit WHERE window_start < ?",
        Timestamp.from(now.minus(rateLimitRetention)));
  }
}
