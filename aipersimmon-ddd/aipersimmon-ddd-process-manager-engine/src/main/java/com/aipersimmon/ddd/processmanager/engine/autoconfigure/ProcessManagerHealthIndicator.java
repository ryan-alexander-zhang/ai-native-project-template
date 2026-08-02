package com.aipersimmon.ddd.processmanager.engine.autoconfigure;

import com.aipersimmon.ddd.processmanager.engine.observe.ProcessBacklog;
import java.time.Duration;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;

/**
 * Reports Process Manager health from the backlog SLIs: DOWN when the store is unreachable, {@code
 * DEGRADED} when there is a redrive backlog, suspended or stuck instances, or the oldest due work
 * has waited past the configured warning threshold, and UP otherwise. A suspended instance degrades
 * health for the same reason a DEAD row does — nothing will happen to it until an operator acts (a
 * suspension caused by effect or deadline exhaustion also leaves a DEAD row, but a parked-input
 * suspension leaves only the instance, so the suspension count must weigh in by itself). DEGRADED
 * is a distinct status so a transient backlog surfaces without failing the aggregate readiness
 * probe.
 */
public final class ProcessManagerHealthIndicator implements HealthIndicator {

  /** A non-standard status: the runtime is serving but has a backlog that needs attention. */
  static final Status DEGRADED = new Status("DEGRADED");

  private final ProcessBacklog backlog;
  private final Duration stuckThreshold;
  private final Duration oldestPendingWarn;

  public ProcessManagerHealthIndicator(
      ProcessBacklog backlog, Duration stuckThreshold, Duration oldestPendingWarn) {
    this.backlog = backlog;
    this.stuckThreshold = stuckThreshold;
    this.oldestPendingWarn = oldestPendingWarn;
  }

  @Override
  public Health health() {
    ProcessBacklog.BacklogSnapshot s;
    try {
      s = backlog.snapshot(stuckThreshold); // one coherent read instead of a query per detail
    } catch (RuntimeException storeUnavailable) {
      return Health.down(storeUnavailable).build();
    }

    boolean degraded =
        s.deadEffects() > 0
            || s.deadDeadlines() > 0
            || s.suspendedInstances() > 0
            || s.stuckInstances() > 0
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
