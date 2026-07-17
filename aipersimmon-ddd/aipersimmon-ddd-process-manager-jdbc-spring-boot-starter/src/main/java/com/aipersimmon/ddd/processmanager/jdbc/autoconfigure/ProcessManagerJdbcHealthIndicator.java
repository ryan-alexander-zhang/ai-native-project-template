package com.aipersimmon.ddd.processmanager.jdbc.autoconfigure;

import com.aipersimmon.ddd.processmanager.jdbc.observe.JdbcProcessBacklog;
import java.time.Duration;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;

/**
 * Reports Process Manager health from the backlog SLIs: DOWN when the store is
 * unreachable, {@code DEGRADED} when there is a redrive backlog, stuck instances, or the oldest due
 * work has waited past the configured warning threshold, and UP otherwise. DEGRADED is a distinct
 * status so a transient backlog surfaces without failing the aggregate readiness probe.
 */
public final class ProcessManagerJdbcHealthIndicator implements HealthIndicator {

    /** A non-standard status: the runtime is serving but has a backlog that needs attention. */
    static final Status DEGRADED = new Status("DEGRADED");

    private final JdbcProcessBacklog backlog;
    private final Duration stuckThreshold;
    private final Duration oldestPendingWarn;

    public ProcessManagerJdbcHealthIndicator(
            JdbcProcessBacklog backlog, Duration stuckThreshold, Duration oldestPendingWarn) {
        this.backlog = backlog;
        this.stuckThreshold = stuckThreshold;
        this.oldestPendingWarn = oldestPendingWarn;
    }

    @Override
    public Health health() {
        long deadEffects;
        long deadDeadlines;
        long suspended;
        long stuck;
        Duration oldestEffect;
        Duration oldestDeadline;
        try {
            deadEffects = backlog.deadEffects();
            deadDeadlines = backlog.deadDeadlines();
            suspended = backlog.suspendedInstances();
            stuck = backlog.stuckInstances(stuckThreshold);
            oldestEffect = backlog.oldestPendingEffectAge();
            oldestDeadline = backlog.oldestPendingDeadlineAge();
        } catch (RuntimeException storeUnavailable) {
            return Health.down(storeUnavailable).build();
        }

        boolean degraded = deadEffects > 0 || deadDeadlines > 0 || stuck > 0
                || oldestEffect.compareTo(oldestPendingWarn) > 0
                || oldestDeadline.compareTo(oldestPendingWarn) > 0;

        return new Health.Builder(degraded ? DEGRADED : Status.UP)
                .withDetail("deadEffects", deadEffects)
                .withDetail("deadDeadlines", deadDeadlines)
                .withDetail("suspendedInstances", suspended)
                .withDetail("stuckInstances", stuck)
                .withDetail("oldestPendingEffectSeconds", oldestEffect.toSeconds())
                .withDetail("oldestPendingDeadlineSeconds", oldestDeadline.toSeconds())
                .build();
    }
}
