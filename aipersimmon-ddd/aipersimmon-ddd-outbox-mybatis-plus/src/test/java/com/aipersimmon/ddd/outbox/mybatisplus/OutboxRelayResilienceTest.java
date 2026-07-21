package com.aipersimmon.ddd.outbox.mybatisplus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.integration.UnknownIntegrationEventException;
import com.aipersimmon.ddd.outbox.DeadLetterStore;
import com.aipersimmon.ddd.outbox.OutboxDispatcher;
import com.aipersimmon.ddd.outbox.OutboxMessage;
import java.sql.Timestamp;
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
 * The MyBatis-Plus backend's counterpart to the JDBC starter's resilience test: the same
 * ordering-under-failure, dead-letter, and replay behaviour, so the two backends are
 * interchangeable. Backoff is disabled (base = 0) so a retried row is eligible again on the next
 * poll and the test can drive attempts deterministically.
 */
@SpringBootTest(
    classes = OutboxRelayResilienceTest.TestApp.class,
    properties = {
      "aipersimmon.ddd.outbox.poll-delay-ms=3600000",
      "aipersimmon.ddd.outbox.max-attempts=2",
      "aipersimmon.ddd.outbox.retry.base-backoff-ms=0",
      "aipersimmon.ddd.outbox.retry.max-backoff-ms=0"
    })
class OutboxRelayResilienceTest {

  @SpringBootConfiguration
  @EnableAutoConfiguration
  static class TestApp {

    @Bean
    ControllableDispatcher controllableDispatcher() {
      return new ControllableDispatcher();
    }
  }

  static class ControllableDispatcher implements OutboxDispatcher {
    final Set<String> failEventIds = ConcurrentHashMap.newKeySet();
    final Set<String> permanentlyFailEventIds = ConcurrentHashMap.newKeySet();
    final List<String> dispatched = new CopyOnWriteArrayList<>();

    @Override
    public void dispatch(OutboxMessage message) {
      dispatched.add(message.eventId());
      if (permanentlyFailEventIds.contains(message.eventId())) {
        throw new UnknownIntegrationEventException(message.type(), message.version());
      }
      if (failEventIds.contains(message.eventId())) {
        throw new IllegalStateException("scripted transient failure for " + message.eventId());
      }
    }
  }

  @Autowired OutboxRelay relay;
  @Autowired JdbcTemplate jdbc;
  @Autowired DeadLetterStore deadLetterStore;
  @Autowired ControllableDispatcher dispatcher;

  @BeforeEach
  void reset() {
    jdbc.update("DELETE FROM aipersimmon_outbox");
    jdbc.update("DELETE FROM aipersimmon_dead_letter");
    dispatcher.dispatched.clear();
    dispatcher.failEventIds.clear();
    dispatcher.permanentlyFailEventIds.clear();
  }

  private void insert(String eventId, String subject, long createdOffsetSeconds) {
    jdbc.update(
        "INSERT INTO aipersimmon_outbox (event_id, source, type, version, payload, occurred_at, "
            + "subject, correlation_id, causation_id, sent, attempts, created_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        eventId,
        "test",
        "SampleEvent",
        1,
        "{}",
        Timestamp.from(Instant.now()),
        subject,
        "corr",
        null,
        false,
        0,
        Timestamp.from(Instant.now().plusSeconds(createdOffsetSeconds)));
  }

  private int outboxCount() {
    return jdbc.queryForObject("SELECT COUNT(*) FROM aipersimmon_outbox", Integer.class);
  }

  private int deadLetterCount() {
    return jdbc.queryForObject("SELECT COUNT(*) FROM aipersimmon_dead_letter", Integer.class);
  }

  @Test
  void aFailedEventHoldsBackLaterEventsOfTheSameAggregate() {
    insert("e1", "agg-1", 0);
    insert("e2", "agg-1", 10);
    dispatcher.failEventIds.add("e1");

    relay.relay();

    assertTrue(dispatcher.dispatched.contains("e1"));
    assertFalse(
        dispatcher.dispatched.contains("e2"),
        "a later event of the same aggregate must not overtake the stuck one");
  }

  @Test
  void movesARowToTheDeadLetterStoreOnceItExhaustsItsRetries() {
    insert("e1", null, 0);
    dispatcher.failEventIds.add("e1");

    relay.relay();
    relay.relay();
    relay.relay();

    assertEquals(2, dispatcher.dispatched.stream().filter("e1"::equals).count());
    assertEquals(0, outboxCount());
    assertEquals(1, deadLetterCount());
    assertEquals(
        "RETRIES_EXHAUSTED",
        jdbc.queryForObject(
            "SELECT reason FROM aipersimmon_dead_letter WHERE event_id = ?", String.class, "e1"));
  }

  @Test
  void deadLettersAPermanentFailureImmediatelyWithoutRetrying() {
    insert("e1", null, 0);
    dispatcher.permanentlyFailEventIds.add("e1");

    relay.relay();
    relay.relay();

    assertEquals(1, dispatcher.dispatched.stream().filter("e1"::equals).count());
    assertEquals(0, outboxCount());
    assertEquals(
        "PERMANENT",
        jdbc.queryForObject(
            "SELECT reason FROM aipersimmon_dead_letter WHERE event_id = ?", String.class, "e1"));
  }

  @Test
  void replayMovesADeadLetterBackToTheOutboxAndItThenDelivers() {
    insert("e1", null, 0);
    dispatcher.permanentlyFailEventIds.add("e1");
    relay.relay();
    assertEquals(1, deadLetterCount());

    dispatcher.permanentlyFailEventIds.clear();
    assertTrue(deadLetterStore.replay("e1"));
    assertFalse(deadLetterStore.replay("missing"));

    assertEquals(1, outboxCount());
    assertEquals(0, deadLetterCount());

    relay.relay();

    assertEquals(
        Integer.valueOf(1),
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM aipersimmon_outbox WHERE sent = TRUE", Integer.class));
  }
}
