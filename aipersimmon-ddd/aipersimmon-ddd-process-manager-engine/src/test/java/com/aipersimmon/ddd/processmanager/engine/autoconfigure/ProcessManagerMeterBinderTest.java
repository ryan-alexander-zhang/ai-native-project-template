package com.aipersimmon.ddd.processmanager.engine.autoconfigure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.aipersimmon.ddd.processmanager.engine.observe.ProcessBacklog;
import com.aipersimmon.ddd.processmanager.engine.store.InMemoryProcessDeadlineStore;
import com.aipersimmon.ddd.processmanager.engine.store.InMemoryProcessEffectStore;
import com.aipersimmon.ddd.processmanager.engine.store.InMemoryProcessInstanceStore;
import com.aipersimmon.ddd.processmanager.engine.store.SuspensionSource;
import com.aipersimmon.ddd.processmanager.model.ProcessInstanceId;
import com.aipersimmon.ddd.processmanager.model.ProcessLifecycle;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * The suspended-instance SLI must cover every source the runtime writes — the gauge set is derived
 * from {@link SuspensionSource}, not hand-listed. This test exists because the list <em>was</em>
 * hand-listed once: it named EFFECT and DEADLINE, and an instance poisoned during parked-input
 * replay sat waiting for an operator while every gauge read zero.
 */
class ProcessManagerMeterBinderTest {

  private static final Instant NOW = Instant.parse("2026-08-02T10:00:00Z");

  private final InMemoryProcessEffectStore effects = new InMemoryProcessEffectStore();
  private final InMemoryProcessDeadlineStore deadlines = new InMemoryProcessDeadlineStore();
  private final InMemoryProcessInstanceStore instances = new InMemoryProcessInstanceStore();
  private final MeterRegistry registry = new SimpleMeterRegistry();

  private void bind() {
    ProcessBacklog backlog =
        new ProcessBacklog(effects, deadlines, instances, Clock.fixed(NOW, ZoneOffset.UTC));
    new ProcessManagerMeterBinder(backlog, Duration.ofMinutes(15), Clock.fixed(NOW, ZoneOffset.UTC))
        .bindTo(registry);
  }

  private void suspend(String instanceId, String source) {
    ProcessInstanceId id = new ProcessInstanceId(instanceId);
    instances.given(id, ProcessLifecycle.RUNNING);
    instances.suspend(id, ProcessLifecycle.RUNNING, "poisoned", source, "work-1", NOW);
  }

  @Test
  void everySourceTheRuntimeWritesHasAGauge() {
    bind();
    for (SuspensionSource source : SuspensionSource.values()) {
      assertNotNull(
          registry
              .find("aipersimmon.process.manager.suspended.instances")
              .tag("source", source.name())
              .gauge(),
          "no gauge for suspension source " + source);
    }
  }

  @Test
  void aParkedInputSuspensionIsVisible() {
    suspend("inst-1", SuspensionSource.PARKED_INPUT.name());
    bind();

    assertEquals(
        1.0,
        registry
            .get("aipersimmon.process.manager.suspended.instances")
            .tag("source", SuspensionSource.PARKED_INPUT.name())
            .gauge()
            .value());
  }

  @Test
  void aSourceOutsideTheEnumLandsInTheOtherBucketInsteadOfDisappearing() {
    suspend("inst-1", "SOMETHING_FROM_A_NEWER_VERSION");
    bind();

    assertEquals(
        1.0,
        registry
            .get("aipersimmon.process.manager.suspended.instances")
            .tag("source", "OTHER")
            .gauge()
            .value(),
        "an unknown source must be counted somewhere; the sum over the tag must equal the total");
  }
}
