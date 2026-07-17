package com.aipersimmon.ddd.processmanager.exception;

/**
 * Thrown when a payload or state codec cannot encode or decode, or when no codec is
 * registered for a requested logical type/version or Java type. A class-name fallback
 * is never attempted.
 */
public final class ProcessSerializationException extends ProcessException {

    public ProcessSerializationException(String message) {
        super(message);
    }

    public ProcessSerializationException(String message, Throwable cause) {
        super(message, cause);
    }
}
