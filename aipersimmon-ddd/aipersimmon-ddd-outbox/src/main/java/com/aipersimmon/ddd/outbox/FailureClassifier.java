package com.aipersimmon.ddd.outbox;

/**
 * Decides whether a failed dispatch is worth retrying. A <em>transient</em> failure
 * (broker unreachable, timeout, deadlock) recovers on its own, so the relay retries it
 * with backoff up to the attempt ceiling; a <em>permanent</em> failure (the message can
 * never be delivered as it stands — an unknown event type, a malformed payload) will
 * fail identically every time, so the relay dead-letters it at once instead of wasting
 * the whole retry budget on it.
 *
 * <p>The default {@link DefaultFailureClassifier} calls only the few provably hopeless
 * causes permanent and treats everything else as transient. Override the bean to refine
 * that — for example to classify a broker's "message too large" or a
 * {@code org.springframework.dao.NonTransientDataAccessException} as permanent.
 */
@FunctionalInterface
public interface FailureClassifier {

    /** Classifies the failure that a {@link OutboxDispatcher} raised. */
    Failure classify(Throwable error);

    /** Whether a failed dispatch should be retried or given up on immediately. */
    enum Failure {
        /** Recovers on its own; retry with backoff until the attempt ceiling. */
        TRANSIENT,
        /** Cannot succeed on retry; dead-letter immediately. */
        PERMANENT
    }
}
