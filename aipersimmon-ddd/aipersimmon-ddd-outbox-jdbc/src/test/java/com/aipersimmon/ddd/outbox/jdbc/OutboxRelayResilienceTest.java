package com.aipersimmon.ddd.outbox.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * Exercises the relay's ordering-under-failure and dead-letter behaviour against
 * H2, using a dispatcher whose failures are scripted per event id.
 */
@SpringBootTest(
        classes = OutboxRelayResilienceTest.TestApp.class,
        properties = {
                "aipersimmon.ddd.outbox.poll-delay-ms=3600000",
                "aipersimmon.ddd.outbox.max-attempts=2"})
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
        final List<String> dispatched = new CopyOnWriteArrayList<>();

        @Override
        public void dispatch(OutboxMessage message) {
            dispatched.add(message.eventId());
            if (failEventIds.contains(message.eventId())) {
                throw new IllegalStateException("scripted failure for " + message.eventId());
            }
        }
    }

    @Autowired
    OutboxRelay relay;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ControllableDispatcher dispatcher;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM aipersimmon_outbox");
        dispatcher.dispatched.clear();
        dispatcher.failEventIds.clear();
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
        assertEquals(Integer.valueOf(0), attempts("e2"), "the held-back event was not attempted");
    }

    @Test
    void stopsSelectingARowOnceItReachesMaxAttempts() {
        insert("e1", null, 0);
        dispatcher.failEventIds.add("e1");

        relay.relay(); // attempt 1 -> attempts = 1
        relay.relay(); // attempt 2 -> attempts = 2, now a dead letter
        relay.relay(); // excluded by the poll, not attempted again

        assertEquals(2, dispatcher.dispatched.stream().filter("e1"::equals).count(),
                "after max-attempts the relay no longer selects the dead-lettered row");
        assertEquals(Integer.valueOf(2), attempts("e1"));
        assertEquals(Integer.valueOf(1),
                jdbc.queryForObject("SELECT COUNT(*) FROM aipersimmon_outbox WHERE sent = FALSE", Integer.class),
                "the dead letter stays in the table for inspection");
    }
}
