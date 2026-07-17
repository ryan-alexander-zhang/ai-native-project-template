package com.aipersimmon.ddd.processmanager.jdbc.autoconfigure;

import com.aipersimmon.ddd.processmanager.jdbc.observe.JdbcProcessBacklog;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Duration;

/**
 * Exports the pull-based backlog SLIs (design-00004 §5.3) as Micrometer gauges over a
 * {@link JdbcProcessBacklog}: the redrive backlog (dead effects/deadlines), how long the oldest
 * due work has waited, and how many instances are suspended or look stuck. Gauges read the store
 * lazily on scrape, so no background polling is added. Latency and conflict-retry meters are
 * push-based and recorded by {@link MicrometerProcessObserver}.
 */
public final class ProcessManagerJdbcMeterBinder implements MeterBinder {

    /** The suspension sources the runtime sets (relay exhaustion, deadline exhaustion). */
    private static final String[] SUSPENSION_SOURCES = {"EFFECT", "DEADLINE"};

    private final JdbcProcessBacklog backlog;
    private final Duration stuckThreshold;

    public ProcessManagerJdbcMeterBinder(JdbcProcessBacklog backlog, Duration stuckThreshold) {
        this.backlog = backlog;
        this.stuckThreshold = stuckThreshold;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder(ProcessManagerMeters.OLDEST_PENDING_EFFECT_AGE, backlog,
                        b -> b.oldestPendingEffectAge().toMillis() / 1000.0)
                .description("Seconds the oldest due-but-undelivered effect has waited")
                .baseUnit("seconds")
                .register(registry);
        Gauge.builder(ProcessManagerMeters.OLDEST_PENDING_DEADLINE_AGE, backlog,
                        b -> b.oldestPendingDeadlineAge().toMillis() / 1000.0)
                .description("Seconds the oldest due-but-unfired deadline has waited")
                .baseUnit("seconds")
                .register(registry);
        Gauge.builder(ProcessManagerMeters.DEAD_EFFECTS, backlog, JdbcProcessBacklog::deadEffects)
                .description("Effects in DEAD awaiting operator redrive")
                .register(registry);
        Gauge.builder(ProcessManagerMeters.DEAD_DEADLINES, backlog, JdbcProcessBacklog::deadDeadlines)
                .description("Deadlines in DEAD awaiting operator redrive")
                .register(registry);
        // One consistent tag key per metric name (Prometheus-friendly): sum over `source` for the total.
        for (String source : SUSPENSION_SOURCES) {
            Gauge.builder(ProcessManagerMeters.SUSPENDED_INSTANCES, backlog,
                            b -> b.suspendedInstancesBySource().getOrDefault(source, 0L))
                    .description("Instances currently SUSPENDED, by suspension source")
                    .tag("source", source)
                    .register(registry);
        }
        Gauge.builder(ProcessManagerMeters.STUCK_INSTANCES, backlog, b -> b.stuckInstances(stuckThreshold))
                .description("Active instances idle past the threshold with no pending work")
                .register(registry);
    }
}
