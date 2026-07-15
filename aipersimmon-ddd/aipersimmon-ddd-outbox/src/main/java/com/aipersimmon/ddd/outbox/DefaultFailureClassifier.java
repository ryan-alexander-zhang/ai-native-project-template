package com.aipersimmon.ddd.outbox;

import com.aipersimmon.ddd.integration.UnknownIntegrationEventException;
import com.fasterxml.jackson.core.JsonProcessingException;

/**
 * Conservative default: a failure is permanent only when retrying provably cannot help.
 * Two such causes exist on the dispatch path — an {@link UnknownIntegrationEventException}
 * (the in-process dispatcher has no local class for the message's {@code (type, version)};
 * no number of retries conjures one) and a Jackson {@link JsonProcessingException} (a
 * payload that will not parse now will not parse later). Everything else is transient,
 * because most dispatch failures (broker down, timeout, a momentary network fault) do
 * recover — so the safe default is to retry rather than discard.
 *
 * <p>Walks the cause chain (bounded, and stopping on a self-referential cause) so a
 * permanent cause wrapped in a generic {@code RuntimeException} is still recognized.
 */
public class DefaultFailureClassifier implements FailureClassifier {

    private static final int MAX_CAUSE_DEPTH = 20;

    @Override
    public Failure classify(Throwable error) {
        Throwable cause = error;
        for (int depth = 0; cause != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (cause instanceof UnknownIntegrationEventException
                    || cause instanceof JsonProcessingException) {
                return Failure.PERMANENT;
            }
            if (cause.getCause() == cause) {
                break;
            }
            cause = cause.getCause();
        }
        return Failure.TRANSIENT;
    }
}
