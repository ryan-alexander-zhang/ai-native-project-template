package com.aipersimmon.ddd.outbox.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.outbox.OutboxDispatcher;
import com.aipersimmon.ddd.outbox.OutboxMessage;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
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
 * A transient failure spaces out its retry: the row's {@code next_attempt_at} is pushed
 * well into the future and the poll skips it until then, instead of re-attempting it
 * every second (the flaw the backoff replaces). Uses a large base backoff so the window
 * is unambiguous.
 */
@SpringBootTest(
        classes = OutboxRelayBackoffTest.TestApp.class,
        properties = {
                "aipersimmon.ddd.outbox.poll-delay-ms=3600000",
                "aipersimmon.ddd.outbox.retry.base-backoff-ms=5000",
                "aipersimmon.ddd.outbox.retry.max-backoff-ms=60000"})
class OutboxRelayBackoffTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {

        @Bean
        AlwaysFailingDispatcher alwaysFailingDispatcher() {
            return new AlwaysFailingDispatcher();
        }
    }

    static class AlwaysFailingDispatcher implements OutboxDispatcher {
        final List<String> dispatched = new CopyOnWriteArrayList<>();

        @Override
        public void dispatch(OutboxMessage message) {
            dispatched.add(message.eventId());
            throw new IllegalStateException("scripted transient failure");
        }
    }

    @Autowired
    OutboxRelay relay;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    AlwaysFailingDispatcher dispatcher;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM aipersimmon_outbox");
        dispatcher.dispatched.clear();
        jdbc.update(
                "INSERT INTO aipersimmon_outbox (event_id, source, type, version, payload, occurred_at, "
                + "subject, correlation_id, causation_id, trace_id, sent, attempts, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                "e1", "test", "SampleEvent", 1, "{}", Timestamp.from(Instant.now()),
                null, "corr", null, null, false, 0, Timestamp.from(Instant.now()));
    }

    @Test
    void aTransientFailureSchedulesTheRetryWellIntoTheFutureAndTheNextPollSkipsIt() {
        Instant before = Instant.now();

        relay.relay(); // attempt 1 fails -> backoff scheduled (base 5s, so at least ~2.5s out)
        relay.relay(); // immediately again: the row is not yet due, so it is skipped

        assertEquals(1, dispatcher.dispatched.size(),
                "the backed-off row must not be re-attempted on the very next poll");

        Instant nextAttemptAt = jdbc.queryForObject(
                "SELECT next_attempt_at FROM aipersimmon_outbox WHERE event_id = ?",
                Timestamp.class, "e1").toInstant();
        assertTrue(nextAttemptAt.isAfter(before.plusSeconds(2)),
                "next_attempt_at is pushed into the future (equal-jitter of a 5s cap is >= 2.5s): "
                        + nextAttemptAt);
    }
}
