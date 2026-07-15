package com.aipersimmon.ddd.outbox.mybatisplus;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.time.Clock;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Deletes outbox rows that were sent longer ago than the retention window, so the
 * table does not grow without bound. Opt-in (see the auto-configuration) because
 * deleting data and the right retention are deployment decisions. Only sent rows are
 * removed; unsent rows are kept for delivery, and dead letters live in their own table
 * (untouched by this purge) for inspection and replay.
 *
 * <p>Guarded by ShedLock like the relay, so one instance runs the purge at a time.
 */
public class OutboxCleanup {

    private static final Logger log = LoggerFactory.getLogger(OutboxCleanup.class);

    private final OutboxMapper mapper;
    private final Clock clock;
    private final long retentionSeconds;

    public OutboxCleanup(OutboxMapper mapper, Clock clock, long retentionSeconds) {
        this.mapper = mapper;
        this.clock = clock;
        this.retentionSeconds = retentionSeconds;
    }

    @Scheduled(fixedDelayString = "${aipersimmon.ddd.outbox.cleanup.poll-delay-ms:3600000}")
    @SchedulerLock(
            name = "${aipersimmon.ddd.outbox.cleanup.lock-name:${spring.application.name:aipersimmon}-outbox-cleanup}",
            lockAtMostFor = "${aipersimmon.ddd.outbox.cleanup.lock-at-most-for:PT10M}")
    public void purge() {
        LambdaQueryWrapper<OutboxRecord> expired = new LambdaQueryWrapper<OutboxRecord>()
                .eq(OutboxRecord::getSent, true)
                .lt(OutboxRecord::getSentAt, clock.instant().minusSeconds(retentionSeconds));
        int deleted = mapper.delete(expired);
        if (deleted > 0) {
            log.info("outbox cleanup removed {} sent rows older than {}s", deleted, retentionSeconds);
        }
    }
}
