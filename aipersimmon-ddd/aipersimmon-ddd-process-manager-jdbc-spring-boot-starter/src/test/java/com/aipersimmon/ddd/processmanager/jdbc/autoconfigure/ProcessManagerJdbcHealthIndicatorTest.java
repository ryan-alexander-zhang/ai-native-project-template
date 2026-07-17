package com.aipersimmon.ddd.processmanager.jdbc.autoconfigure;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.aipersimmon.ddd.processmanager.jdbc.observe.JdbcProcessBacklog;
import com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessDeadlineStore;
import com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessEffectStore;
import com.aipersimmon.ddd.processmanager.jdbc.store.JdbcProcessInstanceStore;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

/** The health indicator's UP / DEGRADED / DOWN branches over an H2 backlog. */
class ProcessManagerJdbcHealthIndicatorTest {

    private EmbeddedDatabase db;
    private JdbcTemplate jdbc;
    private ProcessManagerJdbcHealthIndicator health;

    @BeforeEach
    void setUp() {
        db = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .addScript("classpath:schema.sql")
                .build();
        jdbc = new JdbcTemplate(db);
        JdbcProcessBacklog backlog = new JdbcProcessBacklog(
                new JdbcProcessEffectStore(jdbc), new JdbcProcessDeadlineStore(jdbc),
                new JdbcProcessInstanceStore(jdbc), Clock.systemUTC());
        health = new ProcessManagerJdbcHealthIndicator(backlog, Duration.ofMinutes(15), Duration.ofSeconds(60));
    }

    @AfterEach
    void tearDown() {
        try {
            db.shutdown();
        } catch (RuntimeException alreadyShutdown) {
            // a test may have shut it down to exercise the DOWN branch
        }
    }

    @Test
    void reportsUpWithNoBacklog() {
        assertEquals(Status.UP, health.health().getStatus());
    }

    @Test
    void reportsDegradedWhenAnEffectIsDead() {
        insertDeadEffect();
        assertEquals("DEGRADED", health.health().getStatus().getCode());
    }

    @Test
    void reportsDownWhenTheStoreIsUnreachable() {
        db.shutdown();
        assertEquals(Status.DOWN, health.health().getStatus());
    }

    private void insertDeadEffect() {
        Timestamp now = Timestamp.from(Instant.parse("2026-07-16T00:00:00Z"));
        jdbc.update("""
                INSERT INTO aipersimmon_process_effect (
                    effect_id, instance_id, transition_id, effect_index, effect_kind,
                    payload_type, payload_version, payload, message_id, correlation_id,
                    status, attempts, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                "eff-1", "inst-1", "tr-1", 0, "DISPATCH_COMMAND",
                "some.command", 1, "{}", "eff-1", "corr-1",
                "DEAD", 12, now, now);
    }
}
