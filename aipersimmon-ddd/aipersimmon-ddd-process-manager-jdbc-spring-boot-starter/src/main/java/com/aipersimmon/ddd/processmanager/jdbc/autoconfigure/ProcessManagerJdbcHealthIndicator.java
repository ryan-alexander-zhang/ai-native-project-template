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
        JdbcProcessBacklog.BacklogSnapshot s;
        try {
            s = backlog.snapshot(stuckThreshold); // one coherent read instead of a query per detail
        } catch (RuntimeException storeUnavailable) {
            return Health.down(storeUnavailable).build();
        }

        boolean degraded = s.deadEffects() > 0 || s.deadDeadlines() > 0 || s.stuckInstances() > 0
                || s.oldestPendingEffectAge().compareTo(oldestPendingWarn) > 0
                || s.oldestPendingDeadlineAge().compareTo(oldestPendingWarn) > 0;

        return new Health.Builder(degraded ? DEGRADED : Status.UP)
                .withDetail("deadEffects", s.deadEffects())
                .withDetail("deadDeadlines", s.deadDeadlines())
                .withDetail("suspendedInstances", s.suspendedInstances())
                .withDetail("stuckInstances", s.stuckInstances())
                .withDetail("oldestPendingEffectSeconds", s.oldestPendingEffectAge().toSeconds())
                .withDetail("oldestPendingDeadlineSeconds", s.oldestPendingDeadlineAge().toSeconds())
                .build();
    }
}
