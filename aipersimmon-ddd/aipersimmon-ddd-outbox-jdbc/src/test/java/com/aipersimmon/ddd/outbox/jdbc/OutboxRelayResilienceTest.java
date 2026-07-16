package com.aipersimmon.ddd.outbox.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
 * Exercises the relay's ordering-under-failure, dead-letter, and replay behaviour
 * against H2, using a dispatcher whose failures are scripted per event id. Backoff is
 * disabled (base = 0) so a retried row is eligible again on the next poll and the test
 * can drive attempts deterministically; backoff timing itself is covered by
 * {@link OutboxRelayBackoffTest}.
 */
@SpringBootTest(
        classes = OutboxRelayResilienceTest.TestApp.class,
        properties = {
                "aipersimmon.ddd.outbox.poll-delay-ms=3600000",
                "aipersimmon.ddd.outbox.max-attempts=2",
                "aipersimmon.ddd.outbox.retry.base-backoff-ms=0",
                "aipersimmon.ddd.outbox.retry.max-backoff-ms=0"})
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

    @Autowired
    OutboxRelay relay;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    DeadLetterStore deadLetterStore;
    @Autowired
    ControllableDispatcher dispatcher;

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
                + "subject, correlation_id, causation_id, trace_id, sent, attempts, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                eventId, "test", "SampleEvent", 1, "{}", Timestamp.from(Instant.now()),
                subject, "corr", null, null, false, 0,
                Timestamp.from(Instant.now().plusSeconds(createdOffsetSeconds)));
    }

    private Integer attempts(String eventId) {
        return jdbc.queryForObject(
                "SELECT attempts FROM aipersimmon_outbox WHERE event_id = ?", Integer.class, eventId);
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

        assertTrue(dispatcher.dispatched.contains("e1"), "the oldest event is attempted");
        assertFalse(dispatcher.dispatched.contains("e2"),
                "a later event of the same aggregate must not overtake the stuck one");
        assertEquals(Integer.valueOf(1), attempts("e1"));
        assertNotNull(
                jdbc.queryForObject(
                        "SELECT next_attempt_at FROM aipersimmon_outbox WHERE event_id = ?",
                        Timestamp.class, "e1"),
                "a transient failure schedules the next attempt");
        assertEquals(Integer.valueOf(0), attempts("e2"), "the held-back event was not attempted");
    }

    private boolean sent(String eventId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT sent FROM aipersimmon_outbox WHERE event_id = ?", Boolean.class, eventId));
    }

    @Test
    void aDeadLetteredEarlierEventReleasesItsAggregate() {
        insert("e1", "agg-1", 0);
        insert("e2", "agg-1", 10);
        dispatcher.permanentlyFailEventIds.add("e1");

        relay.relay();

        assertEquals(1, deadLetterCount(), "e1 is dead-lettered (moved out of the outbox)");
        assertTrue(dispatcher.dispatched.contains("e2"),
                "a dead-lettered earlier event must not block its aggregate forever");
        assertTrue(sent("e2"), "e2 is delivered once e1 no longer blocks it");
    }

    @Test
    void aLaterEventProceedsOnlyOnceTheEarlierOneSucceeds() {
        insert("e1", "agg-1", 0);
        insert("e2", "agg-1", 10);
        dispatcher.failEventIds.add("e1");

        relay.relay(); // e1 fails; e2 held behind it
        assertFalse(dispatcher.dispatched.contains("e2"), "e2 waits while e1 is unsent");

        dispatcher.failEventIds.clear(); // the transient cause clears
        relay.relay(); // e1 succeeds, then e2 follows in order

        assertTrue(sent("e1"));
        assertTrue(sent("e2"));
        assertTrue(dispatcher.dispatched.indexOf("e1") < dispatcher.dispatched.indexOf("e2"),
                "e1 is delivered before e2");
    }

    @Test
    void movesARowToTheDeadLetterStoreOnceItExhaustsItsRetries() {
        insert("e1", null, 0);
        dispatcher.failEventIds.add("e1");

        relay.relay(); // attempt 1 -> attempts = 1, retry scheduled
        relay.relay(); // attempt 2 -> reaches max-attempts, dead-lettered
        relay.relay(); // gone from the outbox, not attempted again

        assertEquals(2, dispatcher.dispatched.stream().filter("e1"::equals).count(),
                "after max-attempts the row is dead-lettered and no longer selected");
        assertEquals(0, outboxCount(), "the spent row is moved out of the hot outbox table");
        assertEquals(1, deadLetterCount(), "and preserved in the dead-letter store");
        assertEquals("RETRIES_EXHAUSTED",
                jdbc.queryForObject(
                        "SELECT reason FROM aipersimmon_dead_letter WHERE event_id = ?", String.class, "e1"));
        assertEquals(Integer.valueOf(2),
                jdbc.queryForObject(
                        "SELECT attempts FROM aipersimmon_dead_letter WHERE event_id = ?", Integer.class, "e1"));
    }

    @Test
    void deadLettersAPermanentFailureImmediatelyWithoutRetrying() {
        insert("e1", null, 0);
        dispatcher.permanentlyFailEventIds.add("e1");

        relay.relay();
        relay.relay(); // nothing left to select

        assertEquals(1, dispatcher.dispatched.stream().filter("e1"::equals).count(),
                "a permanent failure is not retried");
        assertEquals(0, outboxCount());
        assertEquals(1, deadLetterCount());
        assertEquals("PERMANENT",
                jdbc.queryForObject(
                        "SELECT reason FROM aipersimmon_dead_letter WHERE event_id = ?", String.class, "e1"));
        String lastError = jdbc.queryForObject(
                "SELECT last_error FROM aipersimmon_dead_letter WHERE event_id = ?", String.class, "e1");
        assertTrue(lastError.contains(UnknownIntegrationEventException.class.getName()),
                "the final error is captured for triage");
    }

    @Test
    void replayMovesADeadLetterBackToTheOutboxAndItThenDelivers() {
        insert("e1", null, 0);
        dispatcher.permanentlyFailEventIds.add("e1");
        relay.relay(); // dead-lettered
        assertEquals(1, deadLetterCount());

        // the cause is "fixed": the dispatcher stops failing, an operator replays it
        dispatcher.permanentlyFailEventIds.clear();
        assertTrue(deadLetterStore.replay("e1"), "an existing dead letter is requeued");
        assertFalse(deadLetterStore.replay("missing"), "a missing id requeues nothing");

        assertEquals(1, outboxCount(), "the message is back in the outbox, unsent");
        assertEquals(0, deadLetterCount());
        assertEquals(Integer.valueOf(0), attempts("e1"), "its delivery bookkeeping is reset");

        relay.relay();

        assertEquals(1,
                jdbc.queryForObject("SELECT COUNT(*) FROM aipersimmon_outbox WHERE sent = TRUE", Integer.class),
                "the replayed message now delivers");
    }
}
