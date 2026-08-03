package com.aipersimmon.ddd.outbox.engine.cleanup;

import com.aipersimmon.ddd.outbox.engine.store.OutboxStore;
import java.time.Clock;
import java.time.Instant;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Deletes outbox rows that were sent longer ago than the retention window, so the table does not
 * grow without bound. Opt-in (see the auto-configuration) because deleting data and the right
 * retention are deployment decisions. Only sent rows are removed; unsent rows are kept for
 * delivery, and dead letters live in their own table (untouched by this purge) for inspection and
 * replay.
 *
 * <p>Guarded by ShedLock like the relay, so one instance runs the purge at a time. Note that
 * {@code @Scheduled} fires this once immediately at startup and holds that lock, so a caller
 * invoking {@link #purge()} directly through the Spring proxy can be silently skipped.
 */
public class OutboxCleanup {

  private static final Logger log = LoggerFactory.getLogger(OutboxCleanup.class);

  private final OutboxStore store;
  private final Clock clock;
  private final long retentionSeconds;
  private final int batchSize;

  public OutboxCleanup(OutboxStore store, Clock clock, long retentionSeconds, int batchSize) {
    if (batchSize < 1) {
      throw new IllegalArgumentException("batchSize must be >= 1, was " + batchSize);
    }
    this.store = store;
    this.clock = clock;
    this.retentionSeconds = retentionSeconds;
    this.batchSize = batchSize;
  }

  @Scheduled(fixedDelayString = "${aipersimmon.ddd.outbox.cleanup.poll-delay-ms:3600000}")
  @SchedulerLock(
      name =
          "${aipersimmon.ddd.outbox.cleanup.lock-name:${spring.application.name:aipersimmon}-outbox-cleanup}",
      lockAtMostFor = "${aipersimmon.ddd.outbox.cleanup.lock-at-most-for:PT10M}")
  public void purge() {
    // Bounded pages rather than one statement: the first purge of a long-lived table would
    // otherwise be a single giant DELETE transaction. Each page is its own small transaction; a
    // run cut short (shutdown, lock expiry) has still permanently removed every page it finished.
    Instant cutoff = clock.instant().minusSeconds(retentionSeconds);
    int total = 0;
    int deleted;
    do {
      deleted = store.deleteSentBefore(cutoff, batchSize);
      total += deleted;
    } while (deleted == batchSize);
    if (total > 0) {
      log.info("outbox cleanup removed {} sent rows older than {}s", total, retentionSeconds);
    }
  }
}
