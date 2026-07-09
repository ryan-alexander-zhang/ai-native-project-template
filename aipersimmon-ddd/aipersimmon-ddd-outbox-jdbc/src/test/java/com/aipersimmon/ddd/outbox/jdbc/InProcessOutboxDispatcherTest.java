package com.aipersimmon.ddd.outbox.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.aipersimmon.ddd.application.IntegrationEvents;
import com.aipersimmon.ddd.integration.IntegrationEvent;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Verifies mode 2 (in-process asynchronous): with the in-process dispatcher
 * enabled, the relay reconstructs the stored event and republishes it to a local
 * {@code @EventListener}.
 */
@SpringBootTest(
        classes = InProcessOutboxDispatcherTest.TestApp.class,
        properties = {
                "aipersimmon.ddd.outbox.poll-delay-ms=3600000",
                "aipersimmon.ddd.outbox.dispatch=in-process"
        })
class InProcessOutboxDispatcherTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {
        @Bean
        CapturingListener capturingListener() {
            return new CapturingListener();
        }
    }

    static class CapturingListener {
        final List<SampleEvent> received = new CopyOnWriteArrayList<>();

        @EventListener
        void on(SampleEvent event) {
            received.add(event);
        }
    }

    record SampleEvent(String orderId) implements IntegrationEvent {
    }

    @Autowired
    IntegrationEvents integrationEvents;
    @Autowired
    OutboxRelay relay;
    @Autowired
    OutboxDispatcher dispatcher;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    CapturingListener listener;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM aipersimmon_outbox");
        listener.received.clear();
    }

    @Test
    void relayRepublishesTheEventInProcess() {
        assertInstanceOf(InProcessOutboxDispatcher.class, dispatcher);

        integrationEvents.publish(new SampleEvent("O-1"));
        relay.relay();

        assertEquals(1, listener.received.size());
        assertEquals("O-1", listener.received.get(0).orderId());
    }
}
