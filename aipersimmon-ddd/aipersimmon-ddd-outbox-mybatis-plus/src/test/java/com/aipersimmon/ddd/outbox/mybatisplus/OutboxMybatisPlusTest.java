package com.aipersimmon.ddd.outbox.mybatisplus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.application.IntegrationEvents;
import com.aipersimmon.ddd.integration.IntegrationEvent;
import com.aipersimmon.ddd.outbox.OutboxDispatcher;
import com.aipersimmon.ddd.outbox.OutboxMessage;
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
 * Exercises the MyBatis-Plus outbox end to end against an in-memory H2 database: a
 * published integration event lands as an unsent row, and the relay dispatches it
 * and marks it sent. The poll delay is set very high so the background scheduler
 * does not fire during the test; the relay is invoked directly instead.
 */
@SpringBootTest(
        classes = OutboxMybatisPlusTest.TestApp.class,
        properties = "aipersimmon.ddd.outbox.poll-delay-ms=3600000")
class OutboxMybatisPlusTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {

        @Bean
        CapturingDispatcher capturingDispatcher() {
            return new CapturingDispatcher();
        }
    }

    static class CapturingDispatcher implements OutboxDispatcher {
        final List<OutboxMessage> messages = new CopyOnWriteArrayList<>();

        @Override
        public void dispatch(OutboxMessage message) {
            messages.add(message);
        }
    }

    record SampleEvent(String orderId) implements IntegrationEvent {
    }

    @Autowired
    IntegrationEvents integrationEvents;
    @Autowired
    OutboxRelay relay;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    CapturingDispatcher dispatcher;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM aipersimmon_outbox");
        dispatcher.messages.clear();
    }

    @Test
    void writesUnsentRowThenRelayDispatchesAndMarksSent() {
        integrationEvents.publish(new SampleEvent("O-1"));

        assertEquals(Integer.valueOf(1),
                jdbc.queryForObject("SELECT COUNT(*) FROM aipersimmon_outbox WHERE sent = FALSE", Integer.class));

        relay.relay();

        assertEquals(1, dispatcher.messages.size());
        OutboxMessage message = dispatcher.messages.get(0);
        assertEquals(SampleEvent.class.getName(), message.type());
        assertTrue(message.payload().contains("O-1"));
        assertEquals(Integer.valueOf(1),
                jdbc.queryForObject("SELECT COUNT(*) FROM aipersimmon_outbox WHERE sent = TRUE", Integer.class));
    }

    @Test
    void autoConfiguresWriterAsIntegrationEventsPublisher() {
        assertInstanceOf(OutboxWriter.class, integrationEvents);
    }
}
