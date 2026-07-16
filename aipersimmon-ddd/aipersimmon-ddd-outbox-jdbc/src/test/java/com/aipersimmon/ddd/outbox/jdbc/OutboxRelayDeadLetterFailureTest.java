package com.aipersimmon.ddd.outbox.jdbc;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.integration.UnknownIntegrationEventException;
import com.aipersimmon.ddd.outbox.DeadLetterStore;
import com.aipersimmon.ddd.outbox.OutboxDispatcher;
import com.aipersimmon.ddd.outbox.OutboxMessage;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * When the dead-letter <em>move</em> itself fails (the dead-letter store is unavailable), a
 * given-up row must not be left with no {@code next_attempt_at} — otherwise the poll re-selects
 * and re-dispatches it every second with no spacing, hammering an already-unhealthy dependency.
 * The relay instead backs the row off and keeps it selectable, so it retries the move at the
 * backoff cadence and self-heals once the dead-letter store recovers.
 */
@SpringBootTest(
        classes = OutboxRelayDeadLetterFailureTest.TestApp.class,
        properties = {
                "aipersimmon.ddd.outbox.poll-delay-ms=3600000",
                "aipersimmon.ddd.outbox.max-attempts=2",
                "aipersimmon.ddd.outbox.retry.base-backoff-ms=60000",
                "aipersimmon.ddd.outbox.retry.max-backoff-ms=60000"})
class OutboxRelayDeadLetterFailureTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {

        @Bean
        CountingPermanentFailureDispatcher dispatcher() {
            return new CountingPermanentFailureDispatcher();
        }

        @Bean
        DeadLetterStore deadLetterStore() {
            return new FailingDeadLetterStore();
        }
    }

    /** Every dispatch fails permanently, so the relay always tries to dead-letter. */
    static class CountingPermanentFailureDispatcher implements OutboxDispatcher {
        final AtomicInteger dispatchCount = new AtomicInteger();

        @Override
        public void dispatch(OutboxMessage message) {
            dispatchCount.incrementAndGet();
            throw new UnknownIntegrationEventException(message.type(), message.version());
        }
    }

    /** Stands in for an unavailable dead-letter table: the move always fails. */
    static class FailingDeadLetterStore implements DeadLetterStore {
        @Override
        public void store(OutboxMessage message, int attempts, Reason reason, String lastError) {
            throw new IllegalStateException("dead-letter table unavailable");
        }

        @Override
        public boolean replay(String eventId) {
            return false;
        }
    }

    @Autowired
    OutboxRelay relay;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    CountingPermanentFailureDispatcher dispatcher;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM aipersimmon_outbox");
        jdbc.update("DELETE FROM aipersimmon_dead_letter");
        dispatcher.dispatchCount.set(0);
    }

    private void insert(String eventId) {
        jdbc.update(
                "INSERT INTO aipersimmon_outbox (event_id, source, type, version, payload, occurred_at, "
                + "subject, correlation_id, causation_id, trace_id, sent, attempts, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                eventId, "test", "SampleEvent", 1, "{}", Timestamp.from(Instant.now()),
                null, "corr", null, null, false, 0, Timestamp.from(Instant.now()));
    }

    @Test
    void aFailedDeadLetterMoveBacksOffInsteadOfHammeringEveryPoll() {
        insert("e1");

        // A failing dead-letter move must not blow up the poll nor leave the row un-scheduled.
        assertDoesNotThrow(relay::relay);
        assertEquals(1, dispatcher.dispatchCount.get(), "dispatched once this poll");

        // The row is neither lost nor (since the move failed) dead-lettered.
        assertEquals(1,
                jdbc.queryForObject("SELECT COUNT(*) FROM aipersimmon_outbox", Integer.class),
                "the row stays in the outbox because the move failed");
        assertEquals(0,
                jdbc.queryForObject("SELECT COUNT(*) FROM aipersimmon_dead_letter", Integer.class));

        // It is backed off (next attempt pushed into the future), not left immediately due.
        Timestamp next = jdbc.queryForObject(
                "SELECT next_attempt_at FROM aipersimmon_outbox WHERE event_id = ?", Timestamp.class, "e1");
        assertNotNull(next, "a failed dead-letter move schedules a backoff instead of hammering");
        assertTrue(next.toInstant().isAfter(Instant.now()), "the next attempt is in the future");

        // And it stays selectable (attempts below max) so it is not silently stranded.
        assertTrue(
                jdbc.queryForObject("SELECT attempts FROM aipersimmon_outbox WHERE event_id = ?",
                        Integer.class, "e1") < 2,
                "the row remains selectable so it retries the move once the store recovers");

        // A subsequent immediate poll does NOT re-dispatch it: it is backing off, not looping.
        relay.relay();
        assertEquals(1, dispatcher.dispatchCount.get(),
                "a backed-off row is not re-dispatched every poll");
    }
}
