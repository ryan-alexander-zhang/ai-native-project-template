package com.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.outbox.engine.relay.OutboxRelayScheduler;
import com.aipersimmon.ddd.processmanager.engine.autoconfigure.ProcessManagerProperties;
import java.time.Duration;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * How this application turns the framework's background pollers off, pinned so the next person to
 * copy a test's property block copies something that works.
 *
 * <p>Every other test here quiets the outbox relay, the process-manager effect relay and the
 * deadline worker so they cannot interfere with what is being asserted. Two ways of writing that
 * were wrong, and both were silent:
 *
 * <ul>
 *   <li><strong>A prefix that does not bind.</strong> The process-manager properties moved out of
 *       {@code …process-manager.jdbc} when the component split into an engine plus backends. An
 *       unknown key is discarded without a word, so eleven test classes were configuring nothing.
 *   <li><strong>A delay used as an off-switch.</strong> {@code @Scheduled(fixedDelay)} runs the
 *       task <em>first</em> and waits afterwards, so a one-hour {@code poll-delay-ms} still polls
 *       once at startup — and if that poll holds the ShedLock lease, a direct {@code relay()} call
 *       is skipped entirely, silently (the library once made the same mistake in its own relay).
 * </ul>
 *
 * <p>Both assertions below exist because neither failure is visible: the first binds nothing, the
 * second changes timing rather than results.
 */
@SpringBootTest
@Import(TestInfrastructure.class)
class BackgroundWorkerControlTest {

  /** The property block every quiet test in this module uses. */
  @Nested
  @TestPropertySource(
      properties = {
        "aipersimmon.ddd.process-manager.effect-relay.enabled=false",
        "aipersimmon.ddd.process-manager.effect-relay.poll-delay=1h",
        "aipersimmon.ddd.process-manager.deadline-worker.enabled=false",
        "aipersimmon.ddd.outbox.relay.enabled=false",
      })
  class WhenTheWorkersAreTurnedOff {

    @Autowired ProcessManagerProperties processManager;
    @Autowired ApplicationContext context;

    /**
     * The prefix the documentation names is the prefix that binds. A value set here must arrive on
     * the properties object; arriving as the 500ms default would mean the key was discarded.
     */
    @Test
    void theProcessManagerPrefixBinds() {
      assertEquals(
          Duration.ofHours(1),
          processManager.getEffectRelay().getPollDelay(),
          "poll-delay did not bind — the prefix in the test properties is not the one the "
              + "properties class declares");
      assertFalse(
          processManager.getEffectRelay().isEnabled(),
          "effect-relay.enabled did not bind, so the relay is still running");
    }

    /** {@code relay.enabled=false} removes the schedule, which is what "off" has to mean. */
    @Test
    void theOutboxRelayIsNotScheduled() {
      assertEquals(
          0,
          context.getBeanNamesForType(OutboxRelayScheduler.class).length,
          "the relay is still scheduled despite relay.enabled=false");
    }
  }

  /** The counter-case: raising the delay is a pacing change, never an off-switch. */
  @Nested
  @TestPropertySource(properties = "aipersimmon.ddd.outbox.poll-delay-ms=3600000")
  class WhenOnlyTheDelayIsRaised {

    @Autowired ApplicationContext context;

    @Test
    void theOutboxRelayIsStillScheduled() {
      assertTrue(
          context.getBeanNamesForType(OutboxRelayScheduler.class).length > 0,
          "a large poll-delay-ms must not be mistaken for a way to switch the relay off");
    }
  }
}
