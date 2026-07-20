package com.aipersimmon.ddd.outbox.mybatisplus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
 * The MyBatis-Plus backend's per-aggregate-ordering-across-backoff test, exercising the
 * correlated NOT EXISTS the poll uses: a later event must not overtake an earlier event
 * of the same subject while it backs off, and an unrelated aggregate must not be blocked.
 * Mirrors the JDBC starter so the two backends behave identically.
 */
@SpringBootTest(
        classes = OutboxRelayBackoffTest.TestApp.class,
        properties = {
                "aipersimmon.ddd.outbox.poll-delay-ms=3600000",
                "aipersimmon.ddd.outbox.max-attempts=5",
                "aipersimmon.ddd.outbox.retry.base-backoff-ms=5000",
                "aipersimmon.ddd.outbox.retry.max-backoff-ms=60000"})
class OutboxRelayBackoffTest {

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
                throw new IllegalStateException("scripted transient failure for " + message.eventId());
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
                + "subject, correlation_id, causation_id, sent, attempts, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                eventId, "test", "SampleEvent", 1, "{}", Timestamp.from(Instant.now()),
                subject, "corr", null, false, 0,
                Timestamp.from(Instant.now().plusSeconds(createdOffsetSeconds)));
    }

    private long dispatchedCount(String eventId) {
        return dispatcher.dispatched.stream().filter(eventId::equals).count();
    }

    @Test
    void aLaterEventWaitsWhileAnEarlierEventOfTheSameSubjectBacksOff() {
        insert("e1", "agg-1", 0);
        insert("e2", "agg-1", 10);
        insert("x1", "agg-2", 5);
        dispatcher.failEventIds.add("e1");

        relay.relay(); // e1 fails -> backoff; x1 sent; e2 held behind e1
        relay.relay(); // e1 not due; e2 still behind it; x1 already sent

        assertEquals(1, dispatchedCount("e1"), "e1 backs off, not retried until due");
        assertFalse(dispatcher.dispatched.contains("e2"),
                "a later event must not overtake the earlier one while it backs off");
        assertEquals(1, dispatchedCount("x1"), "an unrelated aggregate is not blocked");
    }
}
