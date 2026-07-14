package com.aipersimmon.ddd.inbox.mybatisplus;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Deletes inbox rows older than the retention window, so the dedup table does not
 * grow without bound. Opt-in (see the auto-configuration) because deleting data and
 * the right retention are deployment decisions.
 *
 * <p>The retention window must exceed the longest time a broker could redeliver a
 * message: once a key is purged, a later redelivery of that same message is no
 * longer recognised as a duplicate and would be processed again.
 *
 * <p>Not lock-guarded: the delete is a single cutoff-bounded statement, so running
 * it on several instances at once is redundant but harmless (idempotent).
 */
public class InboxCleanup {

    private static final Logger log = LoggerFactory.getLogger(InboxCleanup.class);

    private final InboxMapper mapper;
    private final Clock clock;
    private final long retentionSeconds;

    public InboxCleanup(InboxMapper mapper, Clock clock, long retentionSeconds) {
        this.mapper = mapper;
        this.clock = clock;
        this.retentionSeconds = retentionSeconds;
    }

    @Scheduled(fixedDelayString = "${aipersimmon.ddd.inbox.cleanup.poll-delay-ms:3600000}")
    public void purge() {
        LambdaQueryWrapper<InboxRecord> expired = new LambdaQueryWrapper<InboxRecord>()
                .lt(InboxRecord::getProcessedAt, clock.instant().minusSeconds(retentionSeconds));
        int deleted = mapper.delete(expired);
        if (deleted > 0) {
            log.info("inbox cleanup removed {} rows older than {}s", deleted, retentionSeconds);
        }
    }
}
