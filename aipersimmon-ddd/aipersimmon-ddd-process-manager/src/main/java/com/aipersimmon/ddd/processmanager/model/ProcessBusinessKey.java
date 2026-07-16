package com.aipersimmon.ddd.processmanager.model;

/**
 * The consumer-supplied correlation key that ties a process instance to the business
 * entity it coordinates (for example an order id). The runtime never interprets its
 * content; with {@link ProcessType} it is unique per logical instance. When a key may
 * legitimately run more than once, the run/cycle must be folded into the key by the
 * consumer.
 *
 * @param value the business key; non-blank
 */
public record ProcessBusinessKey(String value) {

    public ProcessBusinessKey {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("process business key value required");
        }
    }
}
