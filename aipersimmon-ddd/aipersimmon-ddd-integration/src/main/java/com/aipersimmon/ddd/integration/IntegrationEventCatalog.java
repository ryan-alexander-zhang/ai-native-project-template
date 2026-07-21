package com.aipersimmon.ddd.integration;

import java.util.Optional;

/**
 * Maps an inbound {@code (type, version)} — the CloudEvents {@code type} plus its {@code
 * dataschemaversion} — to the local Java class to deserialize into. This is the seam that decouples
 * a consumer from the producer's Java class: the consumer looks the published contract up in its
 * own catalogue, instead of loading the producer's class by name.
 *
 * <p>The default catalogue is built by scanning the application's {@link EventType}- annotated
 * {@link IntegrationEvent} classes, keyed by {@code (name, version)} — this is the normal path and
 * needs no configuration. A {@code (type, version)} the catalogue does not contain is a hard miss
 * ({@link Optional#empty()}); the caller dead-letters it rather than guessing (there is
 * deliberately no fully-qualified class-name fallback). Override this bean to add mappings the scan
 * cannot see — dynamically registered types, third-party events, or historical revisions kept alive
 * for migration — but that is the exception, not the default.
 *
 * @see RegistryIntegrationEventCatalog
 */
public interface IntegrationEventCatalog {

  /**
   * @param type the logical event type (CloudEvents {@code type}) from the message
   * @param version the schema revision (CloudEvents {@code dataschemaversion})
   * @return the local class registered for that exact {@code (type, version)}, or {@link
   *     Optional#empty()} if none is — in which case the message is unknown to this consumer and
   *     should be dead-lettered
   */
  Optional<Class<? extends IntegrationEvent>> lookup(String type, int version);
}
