package com.aipersimmon.ddd.messaging.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.aipersimmon.ddd.integration.RegistryIntegrationEventCatalog.Key;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Verifies the reach lookup and the distinct, sorted subscribed-topic set. */
class ExternalizedRoutesTest {

  @Test
  void topicForReturnsTheMappedTopicOrEmptyForLocal() {
    ExternalizedRoutes routes = new ExternalizedRoutes(Map.of(new Key("A", 1), "topic-a"));

    assertThat(routes.topicFor("A", 1)).contains("topic-a");
    assertThat(routes.topicFor("A", 2)).isEmpty();
    assertThat(routes.topicFor("B", 1)).isEmpty();
  }

  @Test
  void topicsAreDistinctAndSorted() {
    Map<Key, String> map = new LinkedHashMap<>();
    map.put(new Key("A", 1), "ordering.events");
    map.put(new Key("B", 1), "ordering.events"); // two events, same topic -> one subscription
    map.put(new Key("C", 1), "inventory.events");
    ExternalizedRoutes routes = new ExternalizedRoutes(map);

    assertThat(routes.topics()).containsExactly("inventory.events", "ordering.events");
    assertThat(routes.isEmpty()).isFalse();
  }

  @Test
  void emptyWhenNothingIsExternalized() {
    ExternalizedRoutes routes = new ExternalizedRoutes(Map.of());

    assertThat(routes.isEmpty()).isTrue();
    assertThat(routes.topics()).isEmpty();
  }
}
