package com.aipersimmon.ddd.inbox.mybatisplus;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

  private final InboxMapper mapper;
  private final Clock clock;
  private final long retentionSeconds;
  private final int batchSize;

  public InboxCleanup(InboxMapper mapper, Clock clock, long retentionSeconds, int batchSize) {
    if (batchSize < 1) {
      throw new IllegalArgumentException("batchSize must be >= 1, was " + batchSize);
    }
    this.mapper = mapper;
    this.clock = clock;
    this.retentionSeconds = retentionSeconds;
    this.batchSize = batchSize;
  }

  @Scheduled(fixedDelayString = "${aipersimmon.ddd.inbox.cleanup.poll-delay-ms:3600000}")
  public void purge() {
    java.time.Instant cutoff = clock.instant().minusSeconds(retentionSeconds);
    int total = 0;
    while (true) {
      List<InboxRecord> page =
          mapper.selectList(
              new LambdaQueryWrapper<InboxRecord>()
                  .select(InboxRecord::getProcessedAt)
                  .lt(InboxRecord::getProcessedAt, cutoff)
                  .orderByAsc(InboxRecord::getProcessedAt)
                  .last("LIMIT " + batchSize));
      if (page.isEmpty()) {
        break;
      }
      total +=
          mapper.delete(
              new LambdaQueryWrapper<InboxRecord>()
                  .le(InboxRecord::getProcessedAt, page.get(page.size() - 1).getProcessedAt()));
    }
    if (total > 0) {
      log.info("inbox cleanup removed {} rows older than {}s", total, retentionSeconds);
    }
  }
}
