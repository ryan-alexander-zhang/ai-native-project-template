package com.aipersimmon.ddd.application;

/**
 * Idempotency guard for consuming a message at most once in effect. A consumer
 * calls this before handling a message; the infrastructure layer records the key
 * and reports whether it had already been handled.
 *
 * <p>Call it inside the same transaction as the processing, so that on failure the
 * record rolls back and the message can be retried on redelivery.
 */
public interface Inbox {

    /**
     * Record the given message key as handled and report whether it had already
     * been handled.
     *
     * @return {@code true} if the key was already recorded (skip processing);
     *         {@code false} if this call recorded it for the first time (proceed)
     */
    boolean alreadyProcessed(String messageKey);
}
