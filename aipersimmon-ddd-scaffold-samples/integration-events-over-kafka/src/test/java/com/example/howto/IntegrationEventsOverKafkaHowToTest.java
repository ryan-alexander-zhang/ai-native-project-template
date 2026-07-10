package com.example.howto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;

/**
 * End-to-end over an embedded Kafka broker: placing a reservation publishes an
 * integration event through the outbox; the relay ships it to Kafka; the consumer
 * bridge reads it, deduplicates via the inbox, and republishes it in process; and
 * the read view is updated exactly once. Polls for the result so the test does not
 * depend on relay and delivery timing.
 */
@SpringBootTest(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "aipersimmon.ddd.outbox.poll-delay-ms=200"
})
@EmbeddedKafka(topics = "reservations", partitions = 1)
class IntegrationEventsOverKafkaHowToTest {

    @Autowired
    ReservationService reservationService;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void reservationTravelsThroughOutboxKafkaAndInboxToTheReadView() throws InterruptedException {
        reservationService.reserve("r1", "SKU-1");

        assertEquals(1, awaitViewRows(30_000));
        // The event was delivered and applied exactly once (inbox-guarded).
        assertEquals("SKU-1", jdbc.queryForObject(
                "SELECT sku FROM reservation_view WHERE reservation_id = 'r1'", String.class));
    }

    private int awaitViewRows(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        int rows = 0;
        while (System.currentTimeMillis() < deadline) {
            rows = jdbc.queryForObject("SELECT COUNT(*) FROM reservation_view", Integer.class);
            if (rows > 0) {
                // Give any duplicate a chance to (wrongly) arrive, to assert exactly-once.
                Thread.sleep(500);
                return jdbc.queryForObject("SELECT COUNT(*) FROM reservation_view", Integer.class);
            }
            Thread.sleep(100);
        }
        return rows;
    }
}
