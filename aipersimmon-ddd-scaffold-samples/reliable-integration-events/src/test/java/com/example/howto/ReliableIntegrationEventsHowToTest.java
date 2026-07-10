package com.example.howto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.aipersimmon.ddd.outbox.OutboxDispatcher;
import com.aipersimmon.ddd.outbox.OutboxMessage;
import com.aipersimmon.ddd.outbox.jdbc.OutboxRelay;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Drives the two patterns against H2. The relay poll delay is set very high so the
 * background scheduler does not fire during the test; the relay is invoked directly.
 */
@SpringBootTest(properties = "aipersimmon.ddd.outbox.poll-delay-ms=3600000")
class ReliableIntegrationEventsHowToTest {

    @TestConfiguration
    static class CapturingConfig {
        @Bean
        CapturingDispatcher capturingDispatcher() {
            return new CapturingDispatcher();
        }
    }

    static class CapturingDispatcher implements OutboxDispatcher {
        final List<OutboxMessage> dispatched = new CopyOnWriteArrayList<>();

        @Override
        public void dispatch(OutboxMessage message) {
            dispatched.add(message);
        }
    }

    @Autowired
    ReservationService reservationService;
    @Autowired
    ReservationProjection projection;
    @Autowired
    OutboxRelay relay;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    CapturingDispatcher dispatcher;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM aipersimmon_outbox");
        jdbc.update("DELETE FROM aipersimmon_inbox");
        jdbc.update("DELETE FROM reservation");
        jdbc.update("DELETE FROM reservation_view");
        dispatcher.dispatched.clear();
    }

    @Test
    void outboxRowIsWrittenWithTheBusinessChange() {
        reservationService.reserve("r1", "SKU-1", false);

        assertEquals(1, count("SELECT COUNT(*) FROM reservation"));
        assertEquals(1, count("SELECT COUNT(*) FROM aipersimmon_outbox WHERE sent = FALSE"));
    }

    @Test
    void failureRollsBackBothTheChangeAndTheOutboxRow() {
        assertThrows(RuntimeException.class,
                () -> reservationService.reserve("r2", "SKU-2", true));

        assertEquals(0, count("SELECT COUNT(*) FROM reservation"));
        assertEquals(0, count("SELECT COUNT(*) FROM aipersimmon_outbox"));
    }

    @Test
    void relayDispatchesUnsentRowsAndMarksThemSent() {
        reservationService.reserve("r3", "SKU-3", false);

        relay.relay();

        assertEquals(1, dispatcher.dispatched.size());
        assertEquals(0, count("SELECT COUNT(*) FROM aipersimmon_outbox WHERE sent = FALSE"));
    }

    @Test
    void inboxMakesTheConsumerIdempotent() {
        projection.apply("evt-1", "SKU-4");
        projection.apply("evt-1", "SKU-4");

        assertEquals(1, count("SELECT COUNT(*) FROM reservation_view"));
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }
}
