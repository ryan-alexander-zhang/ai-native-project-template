package com.aipersimmon.ddd.outbox.engine.autoconfigure;

import com.aipersimmon.ddd.outbox.DeadLetterStore;
import com.aipersimmon.ddd.outbox.engine.observe.OutboxObserver;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

/**
 * A Micrometer-backed {@link OutboxObserver}: claim and dispatch latency distributions, plus
 * counters for the three things worth alerting on — a message given up on, a delivery whose record
 * failed, and a poll that ran out of time. Wired only when a {@code MeterRegistry} is present;
 * otherwise the relay uses {@link OutboxObserver#NOOP}.
 *
 * <p>The dead-letter counter is tagged by reason and every reason is registered up front, so a
 * dashboard has a series at zero to alert on rather than a metric that only appears once something
 * is already lost.
 */
public final class MicrometerOutboxObserver implements OutboxObserver {

  private final Timer claimTimer;
  private final Timer dispatchSuccess;
  private final Timer dispatchFailure;
  private final Map<DeadLetterStore.Reason, Counter> deadLettered =
      new EnumMap<>(DeadLetterStore.Reason.class);
  private final Counter markSentFailures;
  private final Counter released;

  public MicrometerOutboxObserver(MeterRegistry registry) {
    this.claimTimer =
        Timer.builder(OutboxMeters.CLAIM_LATENCY)
            .description("Latency of one relay claim round-trip")
            .register(registry);
    this.dispatchSuccess =
        Timer.builder(OutboxMeters.DISPATCH_LATENCY)
            .description("Latency of one message dispatch")
            .tag("outcome", "success")
            .register(registry);
    this.dispatchFailure =
        Timer.builder(OutboxMeters.DISPATCH_LATENCY)
            .description("Latency of one message dispatch")
            .tag("outcome", "failure")
            .register(registry);
    for (DeadLetterStore.Reason reason : DeadLetterStore.Reason.values()) {
      deadLettered.put(
          reason,
          Counter.builder(OutboxMeters.DEAD_LETTERED)
              .description("Messages given up on and moved to the dead-letter table, by reason")
              .tag("reason", reason.name())
              .register(registry));
    }
    this.markSentFailures =
        Counter.builder(OutboxMeters.MARK_SENT_FAILURES)
            .description("Deliveries the transport accepted but the outbox failed to record")
            .register(registry);
    this.released =
        Counter.builder(OutboxMeters.RELEASED)
            .description("Claimed messages handed back because a poll spent its time budget")
            .register(registry);
  }

  @Override
  public void claimed(int rows, Duration latency) {
    claimTimer.record(latency);
  }

  @Override
  public void dispatched(boolean success, Duration latency) {
    (success ? dispatchSuccess : dispatchFailure).record(latency);
  }

  @Override
  public void deadLettered(DeadLetterStore.Reason reason) {
    Counter counter = deadLettered.get(reason);
    if (counter != null) {
      counter.increment();
    }
  }

  @Override
  public void markSentFailed(int rows) {
    markSentFailures.increment(rows);
  }

  @Override
  public void released(int rows) {
    released.increment(rows);
  }
}
