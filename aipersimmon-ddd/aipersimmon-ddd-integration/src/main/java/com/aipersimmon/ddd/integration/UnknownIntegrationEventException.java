package com.aipersimmon.ddd.integration;

/**
 * Thrown when an inbound message's {@code (type, version)} is not in the
 * {@link IntegrationEventCatalog}: the consumer has no local class for that published
 * contract. It is a permanent failure — retrying will not help and there is no
 * class-name fallback — so the message should be dead-lettered, not reprocessed.
 */
public class UnknownIntegrationEventException extends RuntimeException {

    public UnknownIntegrationEventException(String type, int version) {
        super("no integration event registered for type '" + type + "' version " + version
                + "; the consumer has no local class for this contract and does not guess by class name — "
                + "dead-letter the message");
    }
}
