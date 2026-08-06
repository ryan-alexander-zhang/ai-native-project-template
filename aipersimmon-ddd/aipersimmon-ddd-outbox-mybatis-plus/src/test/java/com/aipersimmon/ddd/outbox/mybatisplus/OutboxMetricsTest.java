package com.aipersimmon.ddd.outbox.mybatisplus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.outbox.DeadLetterStore;
import com.aipersimmon.ddd.outbox.OutboxDispatcher;
import com.aipersimmon.ddd.outbox.OutboxMessage;
import com.aipersimmon.ddd.outbox.engine.autoconfigure.MicrometerOutboxObserver;
import com.aipersimmon.ddd.outbox.engine.autoconfigure.OutboxMeterBinder;
import com.aipersimmon.ddd.outbox.engine.observe.OutboxBacklog;
import com.aipersimmon.ddd.outbox.engine.observe.OutboxObserver;
import com.aipersimmon.ddd.outbox.engine.relay.OutboxRelay;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The two classic outbox alerts — how much is waiting, and how long the oldest has waited — are now
 * readable without hand-written SQL, and the relay reports what it did through a hook. Boot slice:
 * with Micrometer present the starter wires the observer and the gauges; the backlog read itself is
 * framework-free and present either way.
 */
@SpringBootTest(
    classes = OutboxMetricsTest.TestApp.class,
    properties = {
      "aipersimmon.ddd.outbox.relay.enabled=false",
      "aipersimmon.ddd.outbox.max-attempts=2",
      "aipersimmon.ddd.outbox.retry.base-backoff-ms=0",
      "aipersimmon.ddd.outbox.retry.max-backoff-ms=0"
    })
class OutboxMetricsTest {

  @SpringBootConfiguration
  @EnableAutoConfiguration
  static class TestApp {

    @Bean
    MeterRegistry meterRegistry() {
      return new SimpleMeterRegistry();
    }

    @Bean
    ScriptedDispatcher scriptedDispatcher() {
      return new ScriptedDispatcher();
    }
  }

  /** Fails the event ids it is told to, so the give-up path can be driven. */
  static class ScriptedDispatcher implements OutboxDispatcher {
    final Set<String> failing = ConcurrentHashMap.newKeySet();

    @Override
    public void dispatch(OutboxMessage message) {
      if (failing.contains(message.eventId())) {
        throw new IllegalStateException("scripted failure for " + message.eventId());
      }
    }
  }

  /** Records the hook's calls, so what the relay reports can be asserted directly. */
  static class RecordingObserver implements OutboxObserver {
    final List<Integer> claims = new CopyOnWriteArrayList<>();
    final List<Boolean> dispatches = new CopyOnWriteArrayList<>();
    final List<DeadLetterStore.Reason> deadLetters = new CopyOnWriteArrayList<>();
    final List<Integer> markSentFailures = new CopyOnWriteArrayList<>();

    @Override
    public void claimed(int rows, Duration latency) {
      claims.add(rows);
    }

    @Override
    public void dispatched(boolean success, Duration latency) {
      dispatches.add(success);
    }

    @Override
    public void deadLettered(DeadLetterStore.Reason reason) {
      deadLetters.add(reason);
    }

    @Override
    public void markSentFailed(int rows) {
      markSentFailures.add(rows);
    }

    @Override
    public void released(int rows) {}
  }

  @Autowired OutboxBacklog backlog;
  @Autowired OutboxRelay relay;
  @Autowired MeterRegistry registry;
  @Autowired ScriptedDispatcher dispatcher;
  @Autowired JdbcTemplate jdbc;
  @Autowired org.springframework.context.ApplicationContext context;

  @BeforeEach
  void reset() {
    jdbc.update("DELETE FROM aipersimmon_outbox");
    jdbc.update("DELETE FROM aipersimmon_dead_letter");
    dispatcher.failing.clear();
  }

  private void insert(String eventId, boolean sent, int attempts, long createdSecondsAgo) {
    jdbc.update(
        "INSERT INTO aipersimmon_outbox (event_id, source, type, version, payload, occurred_at, "
            + "subject, correlation_id, causation_id, sent, attempts, created_at) "
            + "VALUES (?, 'test', 'SampleEvent', 1, '{}', ?, NULL, 'corr', NULL, ?, ?, ?)",
        eventId,
        Timestamp.from(Instant.now()),
        sent,
        attempts,
        Timestamp.from(Instant.now().minusSeconds(createdSecondsAgo)));
  }

  @Test
  void theBacklogCountsOnlyWorkTheRelayStillIntendsToDeliver() {
    insert("waiting-1", false, 0, 10);
    insert("waiting-2", false, 1, 5);
    insert("already-sent", true, 0, 100);
    insert("given-up", false, 2, 1000); // attempts == max-attempts: it belongs to the dead letters

    assertEquals(
        2,
        backlog.snapshot().pending(),
        "a sent row is done and a spent row is no longer the relay's business; counting either "
            + "would make the alert cry wolf forever");
  }

  @Test
  void theAgeIsThatOfTheOldestMessageStillWaiting() {
    insert("old", false, 0, 3600);
    insert("new", false, 0, 1);

    Duration age = backlog.snapshot().oldestPendingAge();

    assertTrue(
        age.compareTo(Duration.ofMinutes(50)) > 0,
        "the age must come from the oldest waiting row, which is what distinguishes a stalled "
            + "relay from a busy one, but was "
            + age);
  }

  @Test
  void anEmptyOutboxReadsAsZeroRatherThanAsNoReading() {
    OutboxBacklog.Snapshot snapshot = backlog.snapshot();

    assertEquals(0, snapshot.pending());
    assertEquals(
        Duration.ZERO,
        snapshot.oldestPendingAge(),
        "nothing is late, which a gauge can publish — an absent reading it cannot");
  }

  @Test
  void theStarterBindsMicrometerWhenARegistryIsPresent() {
    assertNotNull(
        context.getBean(MicrometerOutboxObserver.class),
        "the relay's hook must be bound to Micrometer when a registry exists");
    assertInstanceOf(
        MeterBinder.class,
        context.getBean(OutboxMeterBinder.class),
        "the gauges arrive as a MeterBinder, which is what Boot binds to every registry");
  }

  @Test
  void theGaugesReadTheStoreOnScrape() {
    insert("waiting-1", false, 0, 42);
    // Bound by hand: this test registers a bare SimpleMeterRegistry, so Boot's MeterBinder
    // post-processor (which needs the actuator metrics auto-configuration) is not in play. What
    // is under test is the binder's own contract — the names it registers and the values it reads.
    SimpleMeterRegistry scrape = new SimpleMeterRegistry();
    context.getBean(OutboxMeterBinder.class).bindTo(scrape);

    assertEquals(
        1.0,
        scrape.get("aipersimmon.outbox.pending").gauge().value(),
        "the gauge reads the store, so it must see the row that is waiting");
    assertTrue(
        scrape.get("aipersimmon.outbox.oldest.pending.age").gauge().value() > 0,
        "and report its age in seconds");
  }

  @Test
  void theRelayReportsWhatItDidThroughTheHook() {
    RecordingObserver observer = new RecordingObserver();
    OutboxRelay observed = relayWith(observer);
    insert("ok-1", false, 0, 1);
    insert("doomed-1", false, 1, 1); // one attempt left before max-attempts
    dispatcher.failing.add("doomed-1");

    observed.relay();

    assertTrue(observer.claims.stream().anyMatch(rows -> rows > 0), "a claim is reported");
    assertTrue(observer.dispatches.contains(Boolean.TRUE), "the delivered message is reported");
    assertTrue(observer.dispatches.contains(Boolean.FALSE), "so is the failed one");
    assertEquals(
        List.of(DeadLetterStore.Reason.RETRIES_EXHAUSTED),
        observer.deadLetters,
        "giving up is reported with its reason — an alert on this firing at all is how a lost "
            + "message becomes known");
  }

  /** The relay as the starter builds it, but reporting to the given hook. */
  private OutboxRelay relayWith(OutboxObserver observer) {
    return new OutboxRelay(
        context.getBean(com.aipersimmon.ddd.outbox.engine.store.OutboxStore.class),
        dispatcher,
        context.getBean(DeadLetterStore.class),
        context.getBean(com.aipersimmon.ddd.outbox.FailureClassifier.class),
        new com.aipersimmon.ddd.outbox.RetryBackoff(0, 0),
        java.time.Clock.systemUTC(),
        100,
        2,
        com.aipersimmon.ddd.outbox.engine.relay.RelayLeases.ownedBy(
            "metrics-test", Duration.ofMinutes(5)),
        com.aipersimmon.ddd.observability.NoOpStoreAndForwardTracer.INSTANCE,
        observer);
  }
}
