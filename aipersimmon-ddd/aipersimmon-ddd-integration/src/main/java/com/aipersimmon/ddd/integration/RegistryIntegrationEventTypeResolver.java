package com.aipersimmon.ddd.integration;

import java.util.Map;

/**
 * Default {@link IntegrationEventTypeResolver}: a registry mapping each known
 * logical type to its local class, with a fully-qualified-class-name fallback for
 * the shared-classpath case (a modular monolith, or contexts that share a published
 * contracts artifact). Register a mapping explicitly when the producer's logical
 * {@code type} does not match a consumer class's simple name.
 */
public final class RegistryIntegrationEventTypeResolver implements IntegrationEventTypeResolver {

    private final Map<String, Class<? extends IntegrationEvent>> byType;

    public RegistryIntegrationEventTypeResolver(Map<String, Class<? extends IntegrationEvent>> byType) {
        this.byType = Map.copyOf(byType);
    }

    @Override
    public Class<? extends IntegrationEvent> resolve(String type) {
        Class<? extends IntegrationEvent> registered = byType.get(type);
        if (registered != null) {
            return registered;
        }
        // Fallback: the type may be a fully-qualified class name available on this
        // classpath (shared contracts artifact / legacy producers).
        try {
            Class<?> loaded = Class.forName(type);
            if (IntegrationEvent.class.isAssignableFrom(loaded)) {
                return loaded.asSubclass(IntegrationEvent.class);
            }
        } catch (ClassNotFoundException ignored) {
            // fall through to the clear error below
        }
        throw new IllegalStateException(
                "no integration event type registered for '" + type + "'; register it with the "
                        + "IntegrationEventTypeResolver, or ensure the type name matches a known event");
    }
}
