package com.aipersimmon.ddd.integration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class EventEnvelopeTest {

    record SampleEvent(String orderId) implements IntegrationEvent {
    }

    private static final SampleEvent PAYLOAD = new SampleEvent("O-1");
    private static final Instant WHEN = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void buildsWithValidMetadata() {
        assertDoesNotThrow(() ->
                new EventEnvelope<>("evt-1", "OrderPlaced", 1, WHEN, "trace-1", PAYLOAD));
    }

    @Test
    void allowsNullTraceId() {
        assertDoesNotThrow(() ->
                new EventEnvelope<>("evt-1", "OrderPlaced", 1, WHEN, null, PAYLOAD));
    }

    @Test
    void rejectsBlankEventId() {
        assertThrows(IllegalArgumentException.class, () ->
                new EventEnvelope<>(" ", "OrderPlaced", 1, WHEN, "trace-1", PAYLOAD));
    }

    @Test
    void rejectsVersionBelowOne() {
        assertThrows(IllegalArgumentException.class, () ->
                new EventEnvelope<>("evt-1", "OrderPlaced", 0, WHEN, "trace-1", PAYLOAD));
    }

    @Test
    void rejectsNullPayload() {
        assertThrows(IllegalArgumentException.class, () ->
                new EventEnvelope<SampleEvent>("evt-1", "OrderPlaced", 1, WHEN, "trace-1", null));
    }
}
