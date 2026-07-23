package com.aipersimmon.ddd.processmanager.jdbc.observe;

import java.time.Duration;

/**
 * A framework-free hook the runtime and relay call to report push-style timing and counter signals
 * — claim/dispatch latency and revision-conflict retries. It lets the JDBC module stay
 * dependency-light (no Micrometer here); the starter binds a Micrometer-backed implementation when
 * a {@code MeterRegistry} is present, otherwise the {@link #NOOP} instance is used so the core
 * carries no metrics cost.
 *
 * <p>Backlog gauges (dead counts, oldest-pending age, suspended/stuck instances) are pull-based and
 * read on demand via {@link JdbcProcessBacklog}; they are not reported through this hook.
 */
public interface ProcessObserver {

  /** A transactional advance was retried because the instance revision moved on under it. */
  void advanceConflictRetry();

  /** A relay poll claimed {@code claimed} due effects; {@code latency} is the claim round-trip. */
  void effectClaimed(int claimed, Duration latency);

  /** A single effect dispatch finished; {@code success} distinguishes delivered from failed. */
  void effectDispatched(boolean success, Duration latency);

  /** A no-op observer, so an unwired core pays nothing. */
  ProcessObserver NOOP =
      new ProcessObserver() {
        @Override
        public void advanceConflictRetry() {}

        @Override
        public void effectClaimed(int claimed, Duration latency) {}

        @Override
        public void effectDispatched(boolean success, Duration latency) {}
      };
}
