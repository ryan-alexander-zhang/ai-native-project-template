package com.aipersimmon.ddd.outbox.engine.autoconfigure;

import com.aipersimmon.ddd.outbox.engine.observe.OutboxBacklog;
import com.aipersimmon.ddd.outbox.engine.observe.OutboxBacklog.Snapshot;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Exports the two classic outbox alerts as Micrometer gauges over an {@link OutboxBacklog}: how
 * many messages are waiting, and how long the oldest has waited. Getting either used to mean
 * hand-written SQL against a table the application does not own.
 *
 * <p>Depth alone does not distinguish a busy system from a broken one — a thousand rows seconds old
 * is healthy, fifty rows an hour old is not — so the age is the gauge to alert on and the depth is
 * the one that says how bad it is.
 *
 * <p>Gauges read the store lazily on scrape, and a short-lived memoized {@link Snapshot} coalesces
 * one scrape's reads into a single query. Latency and give-up meters are push-based and recorded by
 * {@link MicrometerOutboxObserver}.
 */
public final class OutboxMeterBinder implements MeterBinder {

  /** How long a sampled snapshot is reused; long enough to coalesce one scrape's gauge reads. */
  private static final Duration SAMPLE_TTL = Duration.ofSeconds(1);

  private final OutboxBacklog backlog;
  private final Clock clock;

  private volatile Snapshot cached;
  private volatile Instant cachedAt;

  public OutboxMeterBinder(OutboxBacklog backlog, Clock clock) {
    this.backlog = backlog;
    this.clock = clock;
  }

  /** Return a recent snapshot, querying at most once per {@link #SAMPLE_TTL}. */
  private synchronized Snapshot sample() {
    Instant now = clock.instant();
    if (cached == null || Duration.between(cachedAt, now).compareTo(SAMPLE_TTL) >= 0) {
      cached = backlog.snapshot();
      cachedAt = now;
    }
    return cached;
  }

  @Override
  public void bindTo(MeterRegistry registry) {
    Gauge.builder(OutboxMeters.PENDING, this, b -> b.sample().pending())
        .description("Messages written but not yet delivered, and not yet given up on")
        .register(registry);
    Gauge.builder(
            OutboxMeters.OLDEST_PENDING_AGE,
            this,
            b -> b.sample().oldestPendingAge().toMillis() / 1000.0)
        .description("Seconds the oldest undelivered message has been waiting")
        .baseUnit("seconds")
        .register(registry);
  }
}
