package com.aipersimmon.ddd.integration;

import java.util.Map;
import java.util.Optional;

/**
 * Default {@link IntegrationEventCatalog}: an immutable registry mapping each known {@code (type,
 * version)} to its local class. A pair that is not registered is a miss ({@link Optional#empty()})
 * — there is deliberately no fully-qualified-class-name fallback, so an unknown message is
 * dead-lettered rather than deserialized by guessing at a class name.
 */
public final class RegistryIntegrationEventCatalog implements IntegrationEventCatalog {

  /** Registry key: a logical type plus its schema revision. */
  public record Key(String type, int version) {}

  private final Map<Key, Class<? extends IntegrationEvent>> byTypeAndVersion;

  public RegistryIntegrationEventCatalog(
      Map<Key, Class<? extends IntegrationEvent>> byTypeAndVersion) {
    this.byTypeAndVersion = Map.copyOf(byTypeAndVersion);
  }

  @Override
  public Optional<Class<? extends IntegrationEvent>> lookup(String type, int version) {
    return Optional.ofNullable(byTypeAndVersion.get(new Key(type, version)));
  }
}
