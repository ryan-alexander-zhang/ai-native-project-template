package com.aipersimmon.ddd.messaging.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.aipersimmon.ddd.outbox.jdbc.OutboxMessage;
import java.nio.charset.StandardCharsets;
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
            "com.example.OrderPlaced",
            1,
            "{\"orderId\":\"o-1\"}",
            Instant.parse("2026-01-01T00:00:00Z"),
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
        assertEquals("evt-1", record.key());
        assertEquals("{\"orderId\":\"o-1\"}", record.value());
        assertEquals("com.example.OrderPlaced", header(record, IntegrationEventHeaders.TYPE));
        assertEquals("evt-1", header(record, IntegrationEventHeaders.EVENT_ID));
        assertEquals("1", header(record, IntegrationEventHeaders.VERSION));
        assertEquals("trace-9", header(record, IntegrationEventHeaders.TRACE_ID));
        assertEquals("2026-01-01T00:00:00Z", header(record, IntegrationEventHeaders.OCCURRED_AT));
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
