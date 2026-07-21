package com.aipersimmon.ddd.messaging.kafka;

import com.aipersimmon.ddd.integration.RegistryIntegrationEventCatalog.Key;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;

/**
 * The externalization routing table: which integration events are {@code @Externalized}
 * and to which (resolved) Kafka topic. Built once at startup by scanning the application's
 * {@link com.aipersimmon.ddd.integration.IntegrationEvent} classes and resolving each
 * {@code @Externalized} target's {@code ${property}} placeholders against configuration, so
 * the hot path is a map lookup rather than reflection.
 *
 * <p>An event keyed here is EXTERNAL — the {@link RoutingOutboxDispatcher} sends it to the
 * mapped topic and its local delivery comes back through the consumer bridge. An event
 * <em>absent</em> here is LOCAL — routed in-process, never to the broker. {@link #topics()}
 * is the distinct set of subscribed topics the consumer bridge listens on.
 */
public final class ExternalizedRoutes {

    private final Map<Key, String> topicByEvent;
    private final String[] topics;

    public ExternalizedRoutes(Map<Key, String> topicByEvent) {
        this.topicByEvent = Map.copyOf(topicByEvent);
        this.topics = topicByEvent.values().stream()
                .distinct()
                .sorted(Comparator.naturalOrder())
                .toArray(String[]::new);
    }

    /**
     * The topic to externalize this {@code (type, version)} to, or
     * {@link Optional#empty()} if the event is LOCAL (not {@code @Externalized}).
     */
    public Optional<String> topicFor(String type, int version) {
        return Optional.ofNullable(topicByEvent.get(new Key(type, version)));
    }

    /** The distinct set of externalized topics, sorted — the consumer bridge's subscriptions. */
    public String[] topics() {
        return topics.clone();
    }

    /** No event is {@code @Externalized}: the Kafka transport is installed but idle. */
    public boolean isEmpty() {
        return topics.length == 0;
    }

    @Override
    public String toString() {
        return "ExternalizedRoutes" + Arrays.toString(topics);
    }
}
