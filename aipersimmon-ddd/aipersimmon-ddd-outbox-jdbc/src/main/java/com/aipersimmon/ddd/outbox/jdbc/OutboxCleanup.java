package com.aipersimmon.ddd.outbox.jdbc;

import java.sql.Timestamp;
import java.time.Clock;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Deletes outbox rows that were sent longer ago than the retention window, so the table does not
 * grow without bound. Opt-in (see the auto-configuration) because deleting data and the right
 * retention are deployment decisions. Only sent rows are removed; unsent rows are kept for
 * delivery, and dead letters live in their own table (untouched by this purge) for inspection and
 * replay.
 *
 * <p>Guarded by ShedLock like the relay, so one instance runs the purge at a time.
 */
public class OutboxCleanup {

  private static final Logger log = LoggerFactory.getLogger(OutboxCleanup.class);

  private static final String DELETE_SENT =
      "DELETE FROM aipersimmon_outbox WHERE sent = TRUE AND sent_at < ?";

  private final JdbcTemplate jdbc;
  private final Clock clock;
  private final long retentionSeconds;

  public OutboxCleanup(JdbcTemplate jdbc, Clock clock, long retentionSeconds) {
    this.jdbc = jdbc;
    this.clock = clock;
    this.retentionSeconds = retentionSeconds;
  }

  @Scheduled(fixedDelayString = "${aipersimmon.ddd.outbox.cleanup.poll-delay-ms:3600000}")
  @SchedulerLock(
      name =
          "${aipersimmon.ddd.outbox.cleanup.lock-name:${spring.application.name:aipersimmon}-outbox-cleanup}",
      lockAtMostFor = "${aipersimmon.ddd.outbox.cleanup.lock-at-most-for:PT10M}")
  public void purge() {
    Timestamp cutoff = Timestamp.from(clock.instant().minusSeconds(retentionSeconds));
    int deleted = jdbc.update(DELETE_SENT, cutoff);
    if (deleted > 0) {
      log.info("outbox cleanup removed {} sent rows older than {}s", deleted, retentionSeconds);
    }
  }
}
