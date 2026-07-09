package com.example.howto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.aipersimmon.ddd.outbox.jdbc.OutboxRelay;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Mode 2 (in-process asynchronous): with the outbox's in-process dispatcher
 * enabled, the relay republishes each stored event to the local listener, which
 * applies it through the inbox-guarded projection. Reliable delivery (the event
 * waits durably in the outbox) with in-process handling — no broker. The poll
 * delay is set high so only the manual relay call runs.
 */
@SpringBootTest(properties = {
        "aipersimmon.ddd.outbox.poll-delay-ms=3600000",
        "aipersimmon.ddd.outbox.dispatch=in-process"
})
class InProcessDeliveryHowToTest {

    @Autowired
    ReservationService reservationService;
    @Autowired
    OutboxRelay relay;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM aipersimmon_outbox");
        jdbc.update("DELETE FROM aipersimmon_inbox");
        jdbc.update("DELETE FROM reservation");
        jdbc.update("DELETE FROM reservation_view");
    }

    @Test
    void relayDeliversInProcessToTheListener() {
        reservationService.reserve("r1", "SKU-1", false);

        // Nothing is delivered until the relay runs; the event waits in the outbox.
        assertEquals(0, count("SELECT COUNT(*) FROM reservation_view"));

        relay.relay();

        // The relay republished the event in process; the listener applied it once.
        assertEquals(1, count("SELECT COUNT(*) FROM reservation_view"));
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }
}
