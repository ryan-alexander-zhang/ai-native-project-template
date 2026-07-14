package com.aipersimmon.ddd.integration;

/**
 * Resolves an inbound CloudEvents {@code type} (the logical event type carried on
 * the wire) to the local Java class to deserialize into. This is the seam that
 * decouples a consumer from the producer's Java class: the consumer maps the
 * published type name to its own type, instead of loading the producer's class by
 * name.
 *
 * @see RegistryIntegrationEventTypeResolver
 */
public interface IntegrationEventTypeResolver {

    /**
     * @param type the logical event type from the message
     * @return the local class to deserialize the payload into
     * @throws IllegalStateException if the type is not known to this consumer
     */
    Class<? extends IntegrationEvent> resolve(String type);
}
