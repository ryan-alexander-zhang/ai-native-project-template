package com.aipersimmon.ddd.processmanager.codec;

/**
 * The stable, versioned logical type of a persisted payload (a command, a deadline
 * input, or an integration-event body) — for example
 * {@code ("ordering.fulfilment.reserve-stock", 1)}. It is the persistence contract, so
 * a Java class can be renamed without breaking stored rows; a class name is never used
 * as the type. For an integration-event body it must match the event's
 * {@code @EventType} name/version.
 *
 * @param logicalType the logical type name; non-blank
 * @param version     the payload schema version; {@code >= 1}
 */
public record PayloadType(String logicalType, int version) {

    public PayloadType {
        if (logicalType == null || logicalType.isBlank()) {
            throw new IllegalArgumentException("payload logicalType required");
        }
        if (version < 1) {
            throw new IllegalArgumentException("payload version must be >= 1");
        }
    }
}
