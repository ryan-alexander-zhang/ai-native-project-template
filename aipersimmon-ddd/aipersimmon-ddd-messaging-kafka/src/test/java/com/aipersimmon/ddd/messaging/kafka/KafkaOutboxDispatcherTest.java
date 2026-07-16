package com.aipersimmon.ddd.messaging.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.aipersimmon.ddd.outbox.OutboxMessage;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Verifies the Kafka dispatcher maps an outbox message onto a producer record —
 * key, value, and envelope headers — and that a broker send failure surfaces so
 * the outbox relay leaves the row to be retried.
 */
class KafkaOutboxDispatcherTest {

    private final OutboxMessage message = new OutboxMessage(
            "evt-1",
            "/ordering",
            "OrderPlaced",
            1,
            "{\"orderId\":\"o-1\"}",
            Instant.parse("2026-01-01T00:00:00Z"),
            "o-1",
            "corr-1",
            "cause-1",
            "trace-9");

    @Test
    @SuppressWarnings("unchecked")
    void publishesPayloadAsValueWithEnvelopeHeaders() {
        KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
        doReturn(CompletableFuture.completedFuture(null)).when(template).send(any(ProducerRecord.class));

        new KafkaOutboxDispatcher(template, "orders").dispatch(message);

        ArgumentCaptor<ProducerRecord<String, String>> captor =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(template).send(captor.capture());
        ProducerRecord<String, String> record = captor.getValue();

        assertEquals("orders", record.topic());
        assertEquals("o-1", record.key(), "the aggregate subject is the partition key, not the event id");
        assertEquals("{\"orderId\":\"o-1\"}", record.value());
        assertEquals("evt-1", header(record, IntegrationEventHeaders.ID));
        assertEquals("/ordering", header(record, IntegrationEventHeaders.SOURCE));
        assertEquals("1.0", header(record, IntegrationEventHeaders.SPEC_VERSION));
        assertEquals("OrderPlaced", header(record, IntegrationEventHeaders.TYPE));
        assertEquals("o-1", header(record, IntegrationEventHeaders.SUBJECT));
        assertEquals("1", header(record, IntegrationEventHeaders.DATA_SCHEMA_VERSION));
        assertEquals("corr-1", header(record, IntegrationEventHeaders.CORRELATION_ID));
        assertEquals("cause-1", header(record, IntegrationEventHeaders.CAUSATION_ID));
        assertEquals("trace-9", header(record, IntegrationEventHeaders.TRACE_ID));
        assertEquals("o-1", header(record, IntegrationEventHeaders.PARTITION_KEY));
        assertEquals("application/json", header(record, IntegrationEventHeaders.CONTENT_TYPE));
        assertEquals("2026-01-01T00:00:00Z", header(record, IntegrationEventHeaders.TIME));
    }

    @Test
    @SuppressWarnings("unchecked")
    void doesNotBlockTheRelayThreadForeverWhenTheBrokerNeverAcknowledges() {
        KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
        // A send whose ack never arrives (broker partition unwritable, metadata stall, ...).
        doReturn(new CompletableFuture<>()).when(template).send(any(ProducerRecord.class));

        KafkaOutboxDispatcher dispatcher =
                new KafkaOutboxDispatcher(template, "orders", Duration.ofMillis(200));

        // The single relay thread must not be pinned indefinitely on one stuck send: the
        // bounded await surfaces as a (transient) IllegalStateException — not a permanent
        // failure — so the relay leaves the row to be retried on the next poll. The outer
        // preemptive timeout is generous relative to the 200ms send bound; before the fix
        // (an unbounded get) dispatch never returns and this trips.
        assertTimeoutPreemptively(Duration.ofSeconds(3), () ->
                assertThrows(IllegalStateException.class, () -> dispatcher.dispatch(message)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void surfacesBrokerFailureSoTheRowIsRetried() {
        KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
        doReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")))
                .when(template).send(any(ProducerRecord.class));

        KafkaOutboxDispatcher dispatcher = new KafkaOutboxDispatcher(template, "orders");
        assertThrows(IllegalStateException.class, () -> dispatcher.dispatch(message));
    }

    private static String header(ProducerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
