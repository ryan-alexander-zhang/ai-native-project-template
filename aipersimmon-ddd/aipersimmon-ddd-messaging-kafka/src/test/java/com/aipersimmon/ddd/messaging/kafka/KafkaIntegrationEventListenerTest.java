package com.aipersimmon.ddd.messaging.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.aipersimmon.ddd.application.Inbox;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Verifies the consumer bridge reconstructs an event from the type header and JSON
 * payload and republishes it in process, that the inbox deduplicates a redelivery,
 * and that without an inbox every record is republished.
 */
class KafkaIntegrationEventListenerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void reconstructsAndRepublishesThenDeduplicatesRedelivery() throws Exception {
        CapturingPublisher publisher = new CapturingPublisher();
        InMemoryInbox inbox = new InMemoryInbox();
        KafkaIntegrationEventListener listener =
                new KafkaIntegrationEventListener(publisher, mapper, inbox);

        ConsumerRecord<String, String> record = recordFor(new SampleEvent("o-1", "placed"), "evt-1");

        listener.onMessage(record);
        listener.onMessage(record); // redelivery of the same event id

        assertEquals(1, publisher.events.size(), "redelivery must be deduplicated by the inbox");
        SampleEvent republished = assertInstanceOf(SampleEvent.class, publisher.events.get(0));
        assertEquals(new SampleEvent("o-1", "placed"), republished);
    }

    @Test
    void withoutAnInboxEveryRecordIsRepublished() throws Exception {
        CapturingPublisher publisher = new CapturingPublisher();
        KafkaIntegrationEventListener listener =
                new KafkaIntegrationEventListener(publisher, mapper, null);

        ConsumerRecord<String, String> record = recordFor(new SampleEvent("o-1", "placed"), "evt-1");

        listener.onMessage(record);
        listener.onMessage(record);

        assertEquals(2, publisher.events.size());
    }

    private ConsumerRecord<String, String> recordFor(SampleEvent event, String eventId) throws Exception {
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>("orders", 0, 0L, eventId, mapper.writeValueAsString(event));
        record.headers().add(IntegrationEventHeaders.TYPE,
                SampleEvent.class.getName().getBytes(StandardCharsets.UTF_8));
        record.headers().add(IntegrationEventHeaders.EVENT_ID, eventId.getBytes(StandardCharsets.UTF_8));
        return record;
    }

    // --- fixtures ----------------------------------------------------------

    /** A JavaBean event so Jackson maps it by field without needing -parameters. */
    public static class SampleEvent {
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
