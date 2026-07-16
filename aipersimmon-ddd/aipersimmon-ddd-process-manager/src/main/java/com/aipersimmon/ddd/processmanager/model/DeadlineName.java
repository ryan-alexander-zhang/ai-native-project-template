package com.aipersimmon.ddd.processmanager.model;

/**
 * The consumer-defined name of a timer an instance can arm (for example
 * {@code "REVIEW"} or {@code "PAYMENT"}). An instance may hold several named
 * deadlines at once; rescheduling a name bumps its generation and cancelling a name
 * cancels only the current generation.
 *
 * @param value the deadline name; non-blank
 */
public record DeadlineName(String value) {

    public DeadlineName {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("deadline name value required");
        }
    }
}
