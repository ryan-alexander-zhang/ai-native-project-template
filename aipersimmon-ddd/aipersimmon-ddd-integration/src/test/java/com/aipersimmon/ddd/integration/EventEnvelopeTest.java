package com.aipersimmon.ddd.integration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class EventEnvelopeTest {

    record SampleEvent(String orderId) implements IntegrationEvent {
    }

    private static final SampleEvent PAYLOAD = new SampleEvent("O-1");
    private static final Instant WHEN = Instant.parse("2026-01-01T00:00:00Z");

    private static EventEnvelope<SampleEvent> envelope(String eventId, String source, String type,
                                                       String subject, String correlationId) {
        return new EventEnvelope<>(eventId, source, type, 1, WHEN, subject, correlationId, "cause-1", "trace-1", PAYLOAD);
    }

    @Test
    void buildsWithValidMetadata() {
        assertDoesNotThrow(() -> envelope("evt-1", "/ordering", "OrderPlaced", "O-1", "corr-1"));
    }

    @Test
    void allowsNullSubjectCausationAndTrace() {
        assertDoesNotThrow(() ->
                new EventEnvelope<>("evt-1", "/ordering", "OrderPlaced", 1, WHEN, null, "corr-1", null, null, PAYLOAD));
    }

    @Test
    void partitionKeyIsTheSubjectWhenPresentElseEventId() {
        assertEquals("O-1", envelope("evt-1", "/ordering", "OrderPlaced", "O-1", "corr-1").partitionKey());
        assertEquals("evt-1", envelope("evt-1", "/ordering", "OrderPlaced", null, "corr-1").partitionKey());
    }

    @Test
    void rejectsBlankEventId() {
        assertThrows(IllegalArgumentException.class, () -> envelope(" ", "/ordering", "OrderPlaced", "O-1", "corr-1"));
    }

    @Test
    void rejectsBlankSource() {
        assertThrows(IllegalArgumentException.class, () -> envelope("evt-1", " ", "OrderPlaced", "O-1", "corr-1"));
    }

    @Test
    void rejectsBlankCorrelationId() {
        assertThrows(IllegalArgumentException.class, () -> envelope("evt-1", "/ordering", "OrderPlaced", "O-1", " "));
    }

    @Test
    void rejectsNullPayload() {
        assertThrows(IllegalArgumentException.class, () ->
                new EventEnvelope<SampleEvent>("evt-1", "/ordering", "OrderPlaced", 1, WHEN, "O-1", "corr-1", null, null, null));
    }
}
