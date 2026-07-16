package com.aipersimmon.ddd.outbox.jdbc;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.aipersimmon.ddd.outbox.OutboxDispatcher;
import com.aipersimmon.ddd.outbox.OutboxMessage;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A dispatch that reaches the broker but whose {@code sent=TRUE} bookkeeping update then
 * fails must not be treated as a <em>dispatch</em> failure: the message was delivered, so it
 * must never be dead-lettered or counted against its retry budget. The row is simply left
 * unsent to be re-dispatched (an accepted at-least-once duplicate) on the next poll.
 *
 * <p>{@code max-attempts=1} makes the sharp edge explicit: before the fix a single mark-sent
 * failure exhausts the budget and dead-letters a message that was already delivered.
 */
@SpringBootTest(
        classes = OutboxRelayMarkSentFailureTest.TestApp.class,
        properties = {
                "aipersimmon.ddd.outbox.poll-delay-ms=3600000",
                "aipersimmon.ddd.outbox.max-attempts=1"})
class OutboxRelayMarkSentFailureTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {

        @Bean
        CountingDispatcher dispatcher() {
            return new CountingDispatcher();
        }

        /**
         * A JdbcTemplate that fails only the relay's mark-sent update, so dispatch succeeds
         * but recording it does not. Everything else (select, insert, dead-letter) runs normally.
         */
        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource) {
                @Override
                public int update(String sql, Object... args) {
                    if (sql.startsWith("UPDATE aipersimmon_outbox SET sent = TRUE")) {
                        throw new IllegalStateException("simulated failure marking the row sent");
                    }
                    return super.update(sql, args);
                }
            };
        }
    }

    /** Records the message as delivered (the broker ack'd); every dispatch succeeds. */
    static class CountingDispatcher implements OutboxDispatcher {
        final AtomicInteger dispatchCount = new AtomicInteger();

        @Override
        public void dispatch(OutboxMessage message) {
            dispatchCount.incrementAndGet();
        }
    }

    @Autowired
    OutboxRelay relay;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    CountingDispatcher dispatcher;

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
    void aMarkSentFailureAfterASuccessfulDispatchIsNotTreatedAsADispatchFailure() {
        insert("e1");

        assertDoesNotThrow(relay::relay);
        assertEquals(1, dispatcher.dispatchCount.get(), "the message was delivered to the broker");

        // A delivered message is never dead-lettered just because recording it failed.
        assertEquals(0,
                jdbc.queryForObject("SELECT COUNT(*) FROM aipersimmon_dead_letter", Integer.class),
                "a delivered message must not be dead-lettered because mark-sent failed");

        // It is not counted against the retry budget nor scheduled as a failed dispatch.
        assertEquals(1,
                jdbc.queryForObject("SELECT COUNT(*) FROM aipersimmon_outbox", Integer.class),
                "the row stays in the outbox (it could not be marked sent)");
        assertFalse(
                Boolean.TRUE.equals(jdbc.queryForObject(
                        "SELECT sent FROM aipersimmon_outbox WHERE event_id = ?", Boolean.class, "e1")),
                "the row remains unsent because the mark-sent update failed");
        assertEquals(Integer.valueOf(0),
                jdbc.queryForObject("SELECT attempts FROM aipersimmon_outbox WHERE event_id = ?",
                        Integer.class, "e1"),
                "a mark-sent failure is not a dispatch attempt");
        assertNull(
                jdbc.queryForObject("SELECT next_attempt_at FROM aipersimmon_outbox WHERE event_id = ?",
                        Timestamp.class, "e1"),
                "it is not scheduled as a failed-dispatch retry; the next poll re-dispatches it");

        // The next poll re-delivers it (the accepted at-least-once duplicate) rather than losing it.
        relay.relay();
        assertEquals(2, dispatcher.dispatchCount.get(),
                "the unsent row is re-dispatched on the next poll (an at-least-once duplicate)");
    }
}
