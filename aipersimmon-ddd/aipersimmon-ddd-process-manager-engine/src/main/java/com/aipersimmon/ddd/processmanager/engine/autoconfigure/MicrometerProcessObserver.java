package com.aipersimmon.ddd.processmanager.engine.autoconfigure;

import com.aipersimmon.ddd.processmanager.engine.observe.ProcessObserver;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;

/**
 * A Micrometer-backed {@link ProcessObserver}: it records the relay's claim and dispatch latency
 * distributions and counts revision-conflict retries. Wired only when a {@code MeterRegistry} is
 * present; otherwise the runtime and relay use {@link ProcessObserver#NOOP}.
 */
public final class MicrometerProcessObserver implements ProcessObserver {

  private final Timer claimTimer;
  private final Timer dispatchSuccess;
  private final Timer dispatchFailure;
  private final Counter conflictRetries;

  public MicrometerProcessObserver(MeterRegistry registry) {
    this.claimTimer =
        Timer.builder(ProcessManagerMeters.CLAIM_LATENCY)
            .description("Latency of one effect-relay claim round-trip")
            .register(registry);
    this.dispatchSuccess =
        Timer.builder(ProcessManagerMeters.DISPATCH_LATENCY)
            .description("Latency of one effect dispatch")
            .tag("outcome", "success")
            .register(registry);
    this.dispatchFailure =
        Timer.builder(ProcessManagerMeters.DISPATCH_LATENCY)
            .description("Latency of one effect dispatch")
            .tag("outcome", "failure")
            .register(registry);
    this.conflictRetries =
        Counter.builder(ProcessManagerMeters.ADVANCE_CONFLICT_RETRIES)
            .description("Transactional advances retried after a revision conflict")
            .register(registry);
  }

  @Override
  public void advanceConflictRetry() {
    conflictRetries.increment();
  }

  @Override
  public void effectClaimed(int claimed, Duration latency) {
    claimTimer.record(latency);
  }

  @Override
  public void effectDispatched(boolean success, Duration latency) {
    (success ? dispatchSuccess : dispatchFailure).record(latency);
  }
}
