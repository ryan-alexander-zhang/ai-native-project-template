package com.aipersimmon.ddd.messaging.kafka;

/**
 * Kafka header names carrying an integration event's envelope metadata alongside
 * its JSON payload (the record value). The event type lets a consumer pick the
 * class to deserialize into; the event id is the idempotency key; the rest are
 * for tracing. Shared by the producer dispatcher and the consumer bridge.
 */
public final class IntegrationEventHeaders {

    /** Fully qualified class name of the integration event. */
    public static final String TYPE = "aipersimmon_type";
    /** Unique event id, used as the inbox idempotency key. */
    public static final String EVENT_ID = "aipersimmon_event_id";
    /** Envelope schema version. */
    public static final String VERSION = "aipersimmon_version";
    /** Correlation/trace id, if any. */
    public static final String TRACE_ID = "aipersimmon_trace_id";
    /** ISO-8601 instant the event occurred. */
    public static final String OCCURRED_AT = "aipersimmon_occurred_at";

    private IntegrationEventHeaders() {
    }
}
