package com.aipersimmon.ddd.messaging.kafka;

import com.aipersimmon.ddd.integration.RegistryIntegrationEventCatalog.Key;
import com.aipersimmon.ddd.outbox.EventDestinations;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;

/**
 * The externalization routing table: which integration events are {@code @Externalized} and to
 * which (resolved) Kafka topic. Built once at startup by scanning the application's {@link
 * com.aipersimmon.ddd.integration.IntegrationEvent} classes and resolving each
 * {@code @Externalized} target's {@code ${property}} placeholders against configuration, so the hot
 * path is a map lookup rather than reflection.
 *
 * <p>An event keyed here is EXTERNAL — its topic is stamped onto the outbox row when it is
 * published, and the {@link RoutingOutboxDispatcher} later sends it there; its local delivery comes
 * back through the consumer bridge. An event <em>absent</em> here is LOCAL — routed in-process,
 * never to the broker. {@link #topics()} is the distinct set of subscribed topics the consumer
 * bridge listens on.
 *
 * <p>This is the {@link EventDestinations} the writer consults. It is read at <em>publish</em>
 * time, once per event, and never again for that row: the answer is persisted, so a route that
 * later disappears cannot silently turn an externalized event into a local one.
 */
public final class ExternalizedRoutes implements EventDestinations {

  private final Map<Key, String> topicByEvent;
  private final String[] topics;

  public ExternalizedRoutes(Map<Key, String> topicByEvent) {
    this.topicByEvent = Map.copyOf(topicByEvent);
    this.topics =
        topicByEvent.values().stream()
            .distinct()
            .sorted(Comparator.naturalOrder())
            .toArray(String[]::new);
  }

  /**
   * The topic to externalize this {@code (type, version)} to, or {@link Optional#empty()} if the
   * event is LOCAL (not {@code @Externalized}).
   */
  @Override
  public Optional<String> destinationFor(String type, int version) {
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
