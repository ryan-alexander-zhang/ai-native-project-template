package com.aipersimmon.ddd.processmanager.exception;

/**
 * Thrown when an encoded state, input, or effect payload exceeds the configured size cap
 * (design-00004 §5.4 {@code payload.max-bytes}). The cap is a guardrail against oversized
 * payloads bloating the store and slowing claims; keep payloads minimal and store large
 * artifacts by reference.
 */
public final class ProcessPayloadTooLargeException extends ProcessException {

    public ProcessPayloadTooLargeException(String logicalType, int actualBytes, long maxBytes) {
        super("payload of type '" + logicalType + "' is " + actualBytes
                + " bytes, exceeding the configured limit of " + maxBytes + " bytes");
    }
}
