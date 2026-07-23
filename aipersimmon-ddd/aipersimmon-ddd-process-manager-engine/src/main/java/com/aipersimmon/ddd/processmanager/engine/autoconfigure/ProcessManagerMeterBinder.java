package com.aipersimmon.ddd.processmanager.engine.autoconfigure;

import com.aipersimmon.ddd.processmanager.engine.observe.ProcessBacklog;
import com.aipersimmon.ddd.processmanager.engine.observe.ProcessBacklog.BacklogSnapshot;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Exports the pull-based backlog SLIs as Micrometer gauges over a {@link ProcessBacklog}: the
 * redrive backlog (dead effects/deadlines), how long the oldest due work has waited, and how many
 * instances are suspended or look stuck. Gauges read the store lazily on scrape; a short-lived
 * memoized {@link BacklogSnapshot} coalesces one scrape's gauge reads into a single one-pass store
 * sample (instead of a query per gauge, and the suspended-by-source query once instead of once per
 * source). Latency and conflict-retry meters are push-based and recorded by {@link
 * MicrometerProcessObserver}.
 */
public final class ProcessManagerMeterBinder implements MeterBinder {

  /** The suspension sources the runtime sets (relay exhaustion, deadline exhaustion). */
  private static final String[] SUSPENSION_SOURCES = {"EFFECT", "DEADLINE"};

  /** How long a sampled snapshot is reused; long enough to coalesce one scrape's gauge reads. */
  private static final Duration SAMPLE_TTL = Duration.ofSeconds(1);

  private final ProcessBacklog backlog;
  private final Duration stuckThreshold;
  private final Clock clock;

  private volatile BacklogSnapshot cached;
  private volatile Instant cachedAt;

  public ProcessManagerMeterBinder(ProcessBacklog backlog, Duration stuckThreshold, Clock clock) {
    this.backlog = backlog;
    this.stuckThreshold = stuckThreshold;
    this.clock = clock;
  }

  /** Return a recent snapshot, sampling the store at most once per {@link #SAMPLE_TTL}. */
  private synchronized BacklogSnapshot sample() {
    Instant now = clock.instant();
    if (cached == null || Duration.between(cachedAt, now).compareTo(SAMPLE_TTL) >= 0) {
      cached = backlog.snapshot(stuckThreshold);
      cachedAt = now;
    }
    return cached;
  }

  @Override
  public void bindTo(MeterRegistry registry) {
    Gauge.builder(
            ProcessManagerMeters.OLDEST_PENDING_EFFECT_AGE,
            this,
            b -> b.sample().oldestPendingEffectAge().toMillis() / 1000.0)
        .description("Seconds the oldest due-but-undelivered effect has waited")
        .baseUnit("seconds")
        .register(registry);
    Gauge.builder(
            ProcessManagerMeters.OLDEST_PENDING_DEADLINE_AGE,
            this,
            b -> b.sample().oldestPendingDeadlineAge().toMillis() / 1000.0)
        .description("Seconds the oldest due-but-unfired deadline has waited")
        .baseUnit("seconds")
        .register(registry);
    Gauge.builder(ProcessManagerMeters.DEAD_EFFECTS, this, b -> b.sample().deadEffects())
        .description("Effects in DEAD awaiting operator redrive")
        .register(registry);
    Gauge.builder(ProcessManagerMeters.DEAD_DEADLINES, this, b -> b.sample().deadDeadlines())
        .description("Deadlines in DEAD awaiting operator redrive")
        .register(registry);
    // One consistent tag key per metric name (Prometheus-friendly): sum over `source` for the
    // total.
    for (String source : SUSPENSION_SOURCES) {
      Gauge.builder(
              ProcessManagerMeters.SUSPENDED_INSTANCES,
              this,
              b -> b.sample().suspendedBySource().getOrDefault(source, 0L))
          .description("Instances currently SUSPENDED, by suspension source")
          .tag("source", source)
          .register(registry);
    }
    Gauge.builder(ProcessManagerMeters.STUCK_INSTANCES, this, b -> b.sample().stuckInstances())
        .description("Active instances idle past the threshold with no pending work")
        .register(registry);
  }
}
