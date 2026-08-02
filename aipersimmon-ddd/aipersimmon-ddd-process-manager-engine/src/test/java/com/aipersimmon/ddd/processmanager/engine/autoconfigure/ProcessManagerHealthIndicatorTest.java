package com.aipersimmon.ddd.processmanager.engine.autoconfigure;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.aipersimmon.ddd.processmanager.engine.observe.ProcessBacklog;
import com.aipersimmon.ddd.processmanager.engine.store.InMemoryProcessDeadlineStore;
import com.aipersimmon.ddd.processmanager.engine.store.InMemoryProcessEffectStore;
import com.aipersimmon.ddd.processmanager.engine.store.InMemoryProcessInstanceStore;
import com.aipersimmon.ddd.processmanager.engine.store.SuspensionSource;
import com.aipersimmon.ddd.processmanager.model.ProcessInstanceId;
import com.aipersimmon.ddd.processmanager.model.ProcessLifecycle;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

/**
 * A suspended instance is waiting for a human: nothing will happen to it until an operator redrives
 * it, exactly like a DEAD effect or deadline — so it must degrade health the same way. The DOWN and
 * dead-work branches are covered against a real store in the jdbc module's health test; this pins
 * the suspension branch, which used to be missing: an instance poisoned during parked-input replay
 * left health UP.
 */
class ProcessManagerHealthIndicatorTest {

  private static final Instant NOW = Instant.parse("2026-08-02T10:00:00Z");

  private final InMemoryProcessInstanceStore instances = new InMemoryProcessInstanceStore();

  private ProcessManagerHealthIndicator health() {
    ProcessBacklog backlog =
        new ProcessBacklog(
            new InMemoryProcessEffectStore(),
            new InMemoryProcessDeadlineStore(),
            instances,
            Clock.fixed(NOW, ZoneOffset.UTC));
    return new ProcessManagerHealthIndicator(
        backlog, Duration.ofMinutes(15), Duration.ofSeconds(60));
  }

  @Test
  void upWithNothingSuspended() {
    assertEquals(Status.UP, health().health().getStatus());
  }

  @Test
  void aSuspendedInstanceDegradesHealthWhateverItsSource() {
    ProcessInstanceId id = new ProcessInstanceId("inst-1");
    instances.given(id, ProcessLifecycle.RUNNING);
    instances.suspend(
        id,
        ProcessLifecycle.RUNNING,
        "poisoned during replay",
        SuspensionSource.PARKED_INPUT.name(),
        "work-1",
        NOW);

    assertEquals("DEGRADED", health().health().getStatus().getCode());
    assertEquals(1L, health().health().getDetails().get("suspendedInstances"));
  }
}
