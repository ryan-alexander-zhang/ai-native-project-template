package com.aipersimmon.ddd.outbox.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.application.IntegrationEvents;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.integration.EventType;
import com.aipersimmon.ddd.integration.IntegrationEvent;
import com.aipersimmon.ddd.integration.IntegrationEventCatalog;
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
 * Exercises the outbox end to end against an in-memory H2 database: a published
 * integration event lands as an unsent row, and the relay dispatches it and marks
 * it sent. The poll delay is set very high so the background scheduler does not
 * fire during the test; the relay is invoked directly instead.
 */
@SpringBootTest(
        classes = OutboxJdbcTest.TestApp.class,
        properties = "aipersimmon.ddd.outbox.poll-delay-ms=3600000")
class OutboxJdbcTest {

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

    @EventType(name = "com.example.ordering.Sample", version = 1)
    record SampleEvent(String orderId) implements IntegrationEvent {
    }

    @EventType(name = "com.example.ordering.OrderPlaced", version = 1)
    record NamespacedEvent(String orderId) implements IntegrationEvent {
    }

    @Autowired
    IntegrationEvents integrationEvents;
    @Autowired
    OutboxRelay relay;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    CapturingDispatcher dispatcher;
    @Autowired
    IntegrationEventCatalog catalog;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM aipersimmon_outbox");
        dispatcher.messages.clear();
    }

    @Test
    void writesUnsentRowThenRelayDispatchesAndMarksSent() {
        integrationEvents.publish(new SampleEvent("O-1"), CommandContext.root("cmd-1"));

        assertEquals(Integer.valueOf(1),
                jdbc.queryForObject("SELECT COUNT(*) FROM aipersimmon_outbox WHERE sent = FALSE", Integer.class));

        relay.relay();

        assertEquals(1, dispatcher.messages.size());
        OutboxMessage message = dispatcher.messages.get(0);
        assertEquals("com.example.ordering.Sample", message.type(),
                "the declared @EventType logical type, not the Java class name");
        assertTrue(message.payload().contains("O-1"));
        assertEquals("cmd-1", message.correlationId(), "correlation propagated from the command");
        assertEquals("cmd-1", message.causationId(), "caused by the emitting command");
        assertEquals(Integer.valueOf(1),
                jdbc.queryForObject("SELECT COUNT(*) FROM aipersimmon_outbox WHERE sent = TRUE", Integer.class));
    }

    @Test
    void annotatedLogicalTypeIsStampedOnTheWireAndResolvesBackToTheLocalClass() {
        integrationEvents.publish(new NamespacedEvent("O-9"), CommandContext.root("cmd-9"));

        relay.relay();

        assertEquals(1, dispatcher.messages.size());
        OutboxMessage message = dispatcher.messages.get(0);
        assertEquals("com.example.ordering.OrderPlaced", message.type(),
                "the producer stamps the @EventType logical type on the wire, not the Java class name");
        assertSame(NamespacedEvent.class, catalog.lookup(message.type(), message.version()).orElseThrow(),
                "the scanned catalog resolves that same (type, version) back to the local class — "
                        + "before the fix the registry was keyed by simple name and this threw");
    }

    @Test
    void autoConfiguresWriterAsIntegrationEventsPublisher() {
        assertInstanceOf(OutboxWriter.class, integrationEvents);
    }

    @Test
    void publishAsReusesTheEffectIdAsEventIdAndIsIdempotentAcrossRedelivery() {
        // The relay reconstructs an effect context whose messageId IS the persisted effect id.
        // publishAs must stamp that as the event id verbatim (not a fresh random one) and tolerate
        // a redelivery of the same effect — so a crash between the outbox insert and the effect's
        // delivered-mark cannot leave two rows with two different event ids, which would defeat the
        // downstream inbox's dedupe. Before the fix, the effect path used publish() and each
        // redelivery minted a new random event id.
        CommandContext effectContext = new CommandContext("txn-1#0", "corr-1", "cause-1");

        integrationEvents.publishAs(new SampleEvent("O-7"), effectContext);
        integrationEvents.publishAs(new SampleEvent("O-7"), effectContext); // relay redelivers the same effect

        assertEquals(Integer.valueOf(1),
                jdbc.queryForObject("SELECT COUNT(*) FROM aipersimmon_outbox", Integer.class),
                "a redelivered effect must collapse to one outbox row, not two");
        assertEquals("txn-1#0",
                jdbc.queryForObject("SELECT event_id FROM aipersimmon_outbox", String.class),
                "the event id must be the persisted effect id, verbatim — the stable dedupe key downstream");
        assertEquals("corr-1",
                jdbc.queryForObject("SELECT correlation_id FROM aipersimmon_outbox", String.class),
                "publishAs carries the effect context's correlation verbatim");
        assertEquals("cause-1",
                jdbc.queryForObject("SELECT causation_id FROM aipersimmon_outbox", String.class),
                "publishAs carries the effect context's causation verbatim, not the effect id");
    }

    @Test
    void relayPollIsGuardedByShedLock() {
        relay.relay();

        assertEquals(Integer.valueOf(1),
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM shedlock WHERE name = 'aipersimmon-outbox-relay'", Integer.class),
                "the relay poll must acquire a ShedLock lock so only one instance polls at a time");
    }
}
