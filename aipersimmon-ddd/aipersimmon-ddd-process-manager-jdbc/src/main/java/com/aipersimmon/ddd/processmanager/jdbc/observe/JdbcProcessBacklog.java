package com.aipersimmon.ddd.processmanager.jdbc.observe;

import com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessDeadlineStore;
import com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessEffectStore;
import com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessInstanceStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Pull-based backlog SLI reads over the four-table store. It composes the
 * stores' aggregate queries into the health-facing signals — the redrive backlog, how long the
 * oldest due work has waited, and how many instances are suspended or look stuck — computing ages
 * against a {@link Clock}. Read-only and framework-free; the starter's meter binder and health
 * indicator sample it on demand.
 */
public final class JdbcProcessBacklog {

    private final JdbcProcessEffectStore effects;
    private final JdbcProcessDeadlineStore deadlines;
    private final JdbcProcessInstanceStore instances;
    private final Clock clock;

    public JdbcProcessBacklog(
            JdbcProcessEffectStore effects,
            JdbcProcessDeadlineStore deadlines,
            JdbcProcessInstanceStore instances,
            Clock clock) {
        this.effects = effects;
        this.deadlines = deadlines;
        this.instances = instances;
        this.clock = clock;
    }

    /** DEAD effects awaiting operator redrive. */
    public long deadEffects() {
        return effects.countDead();
    }

    /** DEAD deadlines awaiting operator redrive. */
    public long deadDeadlines() {
        return deadlines.countDead();
    }

    /** How long the oldest due-but-undelivered effect has waited past its scheduled time; ZERO if none. */
    public Duration oldestPendingEffectAge() {
        Instant now = clock.instant();
        return effects.oldestDuePending(now).map(due -> nonNegative(Duration.between(due, now))).orElse(Duration.ZERO);
    }

    /** How long the oldest due-but-unfired deadline has waited past its scheduled time; ZERO if none. */
    public Duration oldestPendingDeadlineAge() {
        Instant now = clock.instant();
        return deadlines.oldestDuePending(now).map(due -> nonNegative(Duration.between(due, now))).orElse(Duration.ZERO);
    }

    /** SUSPENDED instances grouped by suspension source (for a per-source metric tag). */
    public Map<String, Long> suspendedInstancesBySource() {
        return instances.countSuspendedBySource();
    }

    /** Total SUSPENDED instances. */
    public long suspendedInstances() {
        return suspendedInstancesBySource().values().stream().mapToLong(Long::longValue).sum();
    }

    /** Active instances last touched more than {@code threshold} ago with no pending work. */
    public long stuckInstances(Duration threshold) {
        return instances.countStuck(clock.instant().minus(threshold));
    }

    private static Duration nonNegative(Duration d) {
        return d.isNegative() ? Duration.ZERO : d;
    }

    /**
     * A coherent one-pass read of every backlog signal, so a health probe or a metrics scrape samples
     * the store once instead of firing a query per gauge/detail (and {@code suspendedInstancesBySource}
     * once, not once per source).
     */
    public BacklogSnapshot snapshot(Duration stuckThreshold) {
        Instant now = clock.instant();
        Map<String, Long> bySource = instances.countSuspendedBySource();
        long suspended = bySource.values().stream().mapToLong(Long::longValue).sum();
        Duration oldestEffect = effects.oldestDuePending(now)
                .map(due -> nonNegative(Duration.between(due, now))).orElse(Duration.ZERO);
        Duration oldestDeadline = deadlines.oldestDuePending(now)
                .map(due -> nonNegative(Duration.between(due, now))).orElse(Duration.ZERO);
        return new BacklogSnapshot(
                effects.countDead(), deadlines.countDead(), bySource, suspended,
                instances.countStuck(now.minus(stuckThreshold)), oldestEffect, oldestDeadline);
    }

    /** An immutable point-in-time read of the backlog SLIs. */
    public record BacklogSnapshot(
            long deadEffects,
            long deadDeadlines,
            Map<String, Long> suspendedBySource,
            long suspendedInstances,
            long stuckInstances,
            Duration oldestPendingEffectAge,
            Duration oldestPendingDeadlineAge) {
    }
}
