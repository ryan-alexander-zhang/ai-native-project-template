package com.aipersimmon.ddd.inbox.jdbc;

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

/** Verifies the opt-in retention cleanup deletes only inbox rows past the window. */
@SpringBootTest(
        classes = InboxCleanupTest.TestApp.class,
        properties = {
                "aipersimmon.ddd.inbox.cleanup.enabled=true",
                "aipersimmon.ddd.inbox.cleanup.retention-seconds=1"})
class InboxCleanupTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {
    }

    @Autowired
    InboxCleanup cleanup;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM aipersimmon_inbox");
    }

    private void insert(String messageKey, Instant processedAt) {
        jdbc.update("INSERT INTO aipersimmon_inbox (consumer, message_key, processed_at) VALUES (?, ?, ?)",
                "svc", messageKey, Timestamp.from(processedAt));
    }

    private Integer count(String messageKey) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM aipersimmon_inbox WHERE message_key = ?", Integer.class, messageKey);
    }

    @Test
    void removesRowsPastRetentionButKeepsRecent() {
        insert("old", Instant.now().minusSeconds(3600));
        insert("recent", Instant.now());

        cleanup.purge();

        assertEquals(Integer.valueOf(0), count("old"), "a row past retention is removed");
        assertEquals(Integer.valueOf(1), count("recent"), "a recent row is kept");
    }
}
