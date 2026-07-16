package com.aipersimmon.ddd.messaging.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.application.Inbox;
import com.aipersimmon.ddd.integration.EventEnvelope;
import com.aipersimmon.ddd.integration.IntegrationEvent;
import com.aipersimmon.ddd.integration.IntegrationEventCatalog;
import com.aipersimmon.ddd.integration.MalformedIntegrationEventException;
import com.aipersimmon.ddd.integration.RegistryIntegrationEventCatalog;
import com.aipersimmon.ddd.integration.RegistryIntegrationEventCatalog.Key;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.PayloadApplicationEvent;

/**
 * Verifies the consumer bridge reconstructs the {@link EventEnvelope} from the
 * headers and JSON payload and republishes it in process, that the inbox
 * deduplicates a redelivery, and that without an inbox every record is republished.
 */
class KafkaIntegrationEventListenerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final IntegrationEventCatalog catalog =
            new RegistryIntegrationEventCatalog(Map.of(new Key("SampleEvent", 1), SampleEvent.class));

    @Test
    void reconstructsAndRepublishesThenDeduplicatesRedelivery() throws Exception {
        CapturingPublisher publisher = new CapturingPublisher();
        InMemoryInbox inbox = new InMemoryInbox();
        KafkaIntegrationEventListener listener =
                new KafkaIntegrationEventListener(publisher, mapper, inbox, catalog);

        ConsumerRecord<String, String> record = recordFor(new SampleEvent("o-1", "placed"), "evt-1");

        listener.onMessage(record);
        listener.onMessage(record); // redelivery of the same event id

        assertEquals(1, publisher.events.size(), "redelivery must be deduplicated by the inbox");
        EventEnvelope<?> envelope = envelopeOf(publisher.events.get(0));
        SampleEvent republished = assertInstanceOf(SampleEvent.class, envelope.payload());
        assertEquals(new SampleEvent("o-1", "placed"), republished);
        assertEquals("evt-1", envelope.eventId());
        assertEquals("/ordering", envelope.source());
        assertEquals("evt-1", envelope.correlationId(),
                "correlationId falls back to the event id when the header is absent");
    }

    @Test
    void withoutAnInboxEveryRecordIsRepublished() throws Exception {
        CapturingPublisher publisher = new CapturingPublisher();
        KafkaIntegrationEventListener listener =
                new KafkaIntegrationEventListener(publisher, mapper, null, catalog);

        ConsumerRecord<String, String> record = recordFor(new SampleEvent("o-1", "placed"), "evt-1");

        listener.onMessage(record);
        listener.onMessage(record);

        assertEquals(2, publisher.events.size());
    }

    @Test
    void rejectsARecordMissingTheRequiredIdHeaderInsteadOfFabricatingOne() throws Exception {
        CapturingPublisher publisher = new CapturingPublisher();
        InMemoryInbox inbox = new InMemoryInbox();
        KafkaIntegrationEventListener listener =
                new KafkaIntegrationEventListener(publisher, mapper, inbox, catalog);

        ConsumerRecord<String, String> record =
                new ConsumerRecord<>("orders", 0, 0L, "o-1", mapper.writeValueAsString(new SampleEvent("o-1", "x")));
        record.headers().add(IntegrationEventHeaders.TYPE, "SampleEvent".getBytes(StandardCharsets.UTF_8));
        // no ce_id header

        assertThrows(MalformedIntegrationEventException.class, () -> listener.onMessage(record),
                "a missing ce_id must be rejected (permanent -> dead-letter), not given a random id");
        assertEquals(0, publisher.events.size(), "nothing is republished");
        assertTrue(inbox.seen.isEmpty(), "the inbox must not be touched with a fabricated id");
    }

    @Test
    void rejectsARecordMissingTheRequiredTypeHeader() throws Exception {
        CapturingPublisher publisher = new CapturingPublisher();
        KafkaIntegrationEventListener listener =
                new KafkaIntegrationEventListener(publisher, mapper, null, catalog);

        ConsumerRecord<String, String> record =
                new ConsumerRecord<>("orders", 0, 0L, "o-1", mapper.writeValueAsString(new SampleEvent("o-1", "x")));
        record.headers().add(IntegrationEventHeaders.ID, "evt-1".getBytes(StandardCharsets.UTF_8));
        // no ce_type header

        assertThrows(MalformedIntegrationEventException.class, () -> listener.onMessage(record));
        assertEquals(0, publisher.events.size());
    }

    private ConsumerRecord<String, String> recordFor(SampleEvent event, String eventId) throws Exception {
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>("orders", 0, 0L, event.orderId, mapper.writeValueAsString(event));
        // CloudEvents binary binding: logical type in ce_type (not the Java class name).
        record.headers().add(IntegrationEventHeaders.TYPE, "SampleEvent".getBytes(StandardCharsets.UTF_8));
        record.headers().add(IntegrationEventHeaders.ID, eventId.getBytes(StandardCharsets.UTF_8));
        record.headers().add(IntegrationEventHeaders.SOURCE, "/ordering".getBytes(StandardCharsets.UTF_8));
        record.headers().add(IntegrationEventHeaders.SPEC_VERSION,
                IntegrationEventHeaders.SPEC_VERSION_VALUE.getBytes(StandardCharsets.UTF_8));
        record.headers().add(IntegrationEventHeaders.DATA_SCHEMA_VERSION, "1".getBytes(StandardCharsets.UTF_8));
        return record;
    }

    /** A well-formed record minus one header, or with one header overridden, for the strict-validation tests. */
    private ConsumerRecord<String, String> recordWithout(String headerToDrop) throws Exception {
        ConsumerRecord<String, String> record = recordFor(new SampleEvent("o-1", "placed"), "evt-1");
        record.headers().remove(headerToDrop);
        return record;
    }

    private ConsumerRecord<String, String> recordWith(String header, String value) throws Exception {
        ConsumerRecord<String, String> record = recordFor(new SampleEvent("o-1", "placed"), "evt-1");
        record.headers().remove(header);
        record.headers().add(header, value.getBytes(StandardCharsets.UTF_8));
        return record;
    }

    private KafkaIntegrationEventListener listener() {
        return new KafkaIntegrationEventListener(new CapturingPublisher(), mapper, null, catalog);
    }

    @Test
    void rejectsAMissingSourceInsteadOfDefaultingToUnknown() throws Exception {
        assertThrows(MalformedIntegrationEventException.class,
                () -> listener().onMessage(recordWithout(IntegrationEventHeaders.SOURCE)));
    }

    @Test
    void rejectsAMissingOrUnsupportedSpecVersion() throws Exception {
        assertThrows(MalformedIntegrationEventException.class,
                () -> listener().onMessage(recordWithout(IntegrationEventHeaders.SPEC_VERSION)));
        assertThrows(MalformedIntegrationEventException.class,
                () -> listener().onMessage(recordWith(IntegrationEventHeaders.SPEC_VERSION, "0.3")));
    }

    @Test
    void rejectsAMissingOrInvalidSchemaVersion() throws Exception {
        assertThrows(MalformedIntegrationEventException.class,
                () -> listener().onMessage(recordWithout(IntegrationEventHeaders.DATA_SCHEMA_VERSION)));
        assertThrows(MalformedIntegrationEventException.class,
                () -> listener().onMessage(recordWith(IntegrationEventHeaders.DATA_SCHEMA_VERSION, "not-a-number")));
        assertThrows(MalformedIntegrationEventException.class,
                () -> listener().onMessage(recordWith(IntegrationEventHeaders.DATA_SCHEMA_VERSION, "0")));
    }

    @Test
    void rejectsAnUnparseableTimeButAcceptsItsAbsence() throws Exception {
        assertThrows(MalformedIntegrationEventException.class,
                () -> listener().onMessage(recordWith(IntegrationEventHeaders.TIME, "yesterday")));

        // absent ce_time is tolerated: it falls back to the record timestamp, not a failure
        CapturingPublisher publisher = new CapturingPublisher();
        KafkaIntegrationEventListener listener =
                new KafkaIntegrationEventListener(publisher, mapper, null, catalog);
        listener.onMessage(recordWithout(IntegrationEventHeaders.TIME));
        assertEquals(1, publisher.events.size(), "a record without ce_time is still delivered");
    }

    private static EventEnvelope<?> envelopeOf(Object published) {
        PayloadApplicationEvent<?> event = assertInstanceOf(PayloadApplicationEvent.class, published);
        return assertInstanceOf(EventEnvelope.class, event.getPayload());
    }

    // --- fixtures ----------------------------------------------------------

    /** A JavaBean event so Jackson maps it by field without needing -parameters. */
    public static class SampleEvent implements IntegrationEvent {
        public String orderId;
        public String status;

        public SampleEvent() {
        }

        public SampleEvent(String orderId, String status) {
            this.orderId = orderId;
            this.status = status;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof SampleEvent e
                    && Objects.equals(orderId, e.orderId)
                    && Objects.equals(status, e.status);
        }

        @Override
        public int hashCode() {
            return Objects.hash(orderId, status);
        }
    }

    static final class CapturingPublisher implements ApplicationEventPublisher {
        final List<Object> events = new ArrayList<>();

        @Override
        public void publishEvent(Object event) {
            events.add(event);
        }

        @Override
        public void publishEvent(ApplicationEvent event) {
            events.add(event);
        }
    }

    static final class InMemoryInbox implements Inbox {
        private final Set<String> seen = new HashSet<>();

        @Override
        public boolean alreadyProcessed(String messageKey) {
            return !seen.add(messageKey);
        }
    }
}
