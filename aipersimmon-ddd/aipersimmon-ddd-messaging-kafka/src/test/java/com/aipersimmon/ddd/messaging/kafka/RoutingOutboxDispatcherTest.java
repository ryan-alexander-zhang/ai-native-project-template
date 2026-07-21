package com.aipersimmon.ddd.messaging.kafka;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.aipersimmon.ddd.integration.RegistryIntegrationEventCatalog.Key;
import com.aipersimmon.ddd.outbox.OutboxDispatcher;
import com.aipersimmon.ddd.outbox.OutboxMessage;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Verifies the router sends {@code @Externalized} events to their topic (and only there — no
 * in-process double-delivery) and keeps LOCAL events in process, keyed by the message's {@code
 * (type, version)}.
 */
class RoutingOutboxDispatcherTest {

  private final OutboxDispatcher localLeg = mock(OutboxDispatcher.class);
  private final KafkaOutboxDispatcher externalLeg = mock(KafkaOutboxDispatcher.class);
  private final ExternalizedRoutes routes =
      new ExternalizedRoutes(Map.of(new Key("com.example.OrderPlaced", 1), "ordering.events"));
  private final RoutingOutboxDispatcher router =
      new RoutingOutboxDispatcher(localLeg, externalLeg, routes);

  @Test
  void externalizedEventGoesToItsTopicAndNotInProcess() {
    OutboxMessage message = message("com.example.OrderPlaced", 1);

    router.dispatch(message);

    verify(externalLeg).dispatch(message, "ordering.events");
    verifyNoInteractions(localLeg);
  }

  @Test
  void localEventIsRepublishedInProcessAndNeverToTheBroker() {
    OutboxMessage message = message("com.example.InternalOnly", 1);

    router.dispatch(message);

    verify(localLeg).dispatch(message);
    verifyNoInteractions(externalLeg);
  }

  @Test
  void versionIsPartOfTheRoutingKey() {
    // Same type name, different schema version: not the externalized (name, version) pair.
    OutboxMessage message = message("com.example.OrderPlaced", 2);

    router.dispatch(message);

    verify(localLeg).dispatch(message);
    verifyNoInteractions(externalLeg);
  }

  private static OutboxMessage message(String type, int version) {
    return new OutboxMessage(
        "evt-1",
        "/ordering",
        type,
        version,
        "{}",
        Instant.parse("2026-01-01T00:00:00Z"),
        "o-1",
        "corr-1",
        "cause-1");
  }
}
