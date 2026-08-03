package com.aipersimmon.ddd.inbox.jdbc;

import java.sql.Timestamp;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Deletes inbox rows older than the retention window, so the dedup table does not grow without
 * bound. Opt-in (see the auto-configuration) because deleting data and the right retention are
 * deployment decisions.
 *
 * <p>The retention window must exceed the longest time a broker could redeliver a message: once a
 * key is purged, a later redelivery of that same message is no longer recognised as a duplicate and
 * would be processed again.
 *
 * <p>Not lock-guarded: every delete is cutoff-bounded, so running it on several instances at once
 * is redundant but harmless (idempotent).
 *
 * <p>Deletes in time-sliced pages rather than one statement, because the primary key is composite —
 * the select-ids-then-delete-by-key page other components use has no cheap portable form here. Each
 * page reads the oldest {@code batchSize} timestamps below the cutoff and deletes everything up to
 * the last of them; timestamp ties can make a page slightly larger than asked, which is harmless —
 * the point is that the first purge of a long-lived table is many small transactions, not one giant
 * one.
 */
public class InboxCleanup {

  private static final Logger log = LoggerFactory.getLogger(InboxCleanup.class);

  private static final String OLDEST_PAGE_CEILING =
      "SELECT MAX(processed_at) FROM (SELECT processed_at FROM aipersimmon_inbox "
          + "WHERE processed_at < ? ORDER BY processed_at LIMIT ";
  private static final String DELETE_PAGE = "DELETE FROM aipersimmon_inbox WHERE processed_at <= ?";

  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final long retentionSeconds;
  private final int batchSize;

  public InboxCleanup(JdbcTemplate jdbc, Clock clock, long retentionSeconds, int batchSize) {
    if (batchSize < 1) {
      throw new IllegalArgumentException("batchSize must be >= 1, was " + batchSize);
    }
    this.jdbc = jdbc;
    this.clock = clock;
    this.retentionSeconds = retentionSeconds;
    this.batchSize = batchSize;
  }

  @Scheduled(fixedDelayString = "${aipersimmon.ddd.inbox.cleanup.poll-delay-ms:3600000}")
  public void purge() {
    Timestamp cutoff = Timestamp.from(clock.instant().minusSeconds(retentionSeconds));
    int total = 0;
    while (true) {
      Timestamp ceiling =
          jdbc.queryForObject(OLDEST_PAGE_CEILING + batchSize + ") page", Timestamp.class, cutoff);
      if (ceiling == null) {
        break;
      }
      total += jdbc.update(DELETE_PAGE, ceiling);
    }
    if (total > 0) {
      log.info("inbox cleanup removed {} rows older than {}s", total, retentionSeconds);
    }
  }
}
