package com.aipersimmon.ddd.outbox.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Verifies the opt-in retention cleanup deletes only sent rows past the window and
 * keeps recent sent rows and all unsent rows (including dead letters).
 */
@SpringBootTest(
        classes = OutboxCleanupTest.TestApp.class,
        properties = {
                "aipersimmon.ddd.outbox.poll-delay-ms=3600000",
                "aipersimmon.ddd.outbox.cleanup.enabled=true",
                "aipersimmon.ddd.outbox.cleanup.retention-seconds=1"})
class OutboxCleanupTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {
    }

    @Autowired
    OutboxCleanup cleanup;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM aipersimmon_outbox");
    }

    private void insert(String eventId, boolean sent, Instant sentAt) {
        jdbc.update(
                "INSERT INTO aipersimmon_outbox (event_id, source, type, version, payload, occurred_at, "
                + "subject, correlation_id, causation_id, trace_id, sent, attempts, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                eventId, "test", "SampleEvent", 1, "{}", Timestamp.from(Instant.now()),
                null, "corr", null, null, sent, 0, Timestamp.from(Instant.now()));
        if (sentAt != null) {
            jdbc.update("UPDATE aipersimmon_outbox SET sent_at = ? WHERE event_id = ?",
                    Timestamp.from(sentAt), eventId);
        }
    }

    private Integer count(String eventId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM aipersimmon_outbox WHERE event_id = ?", Integer.class, eventId);
    }

    @Test
    void removesSentRowsPastRetentionButKeepsRecentAndUnsent() {
        insert("old-sent", true, Instant.now().minusSeconds(3600));
        insert("recent-sent", true, Instant.now());
        insert("unsent", false, null);

        cleanup.purge();

        assertEquals(Integer.valueOf(0), count("old-sent"), "a sent row past retention is removed");
        assertEquals(Integer.valueOf(1), count("recent-sent"), "a recently sent row is kept");
        assertEquals(Integer.valueOf(1), count("unsent"), "an unsent row is never removed");
    }
}
