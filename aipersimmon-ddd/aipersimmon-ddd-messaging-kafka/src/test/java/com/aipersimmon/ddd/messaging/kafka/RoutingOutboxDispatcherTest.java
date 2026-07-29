package com.aipersimmon.ddd.messaging.kafka;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.aipersimmon.ddd.outbox.OutboxDispatcher;
import com.aipersimmon.ddd.outbox.OutboxMessage;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The router reads the destination off the row: a row that names one goes to that topic and only
 * there (no in-process double-delivery), a row that names none is republished in process.
 *
 * <p>What it deliberately does <em>not</em> do is consult the routing table. That table is read
 * once, when the event is published, and the answer is stored — so a route that later disappears
 * cannot turn an externalized event into a local one. Which events are in the table is {@link
 * ExternalizedRoutesTest}'s subject; that the writer stamps the answer is the outbox writer's.
 */
class RoutingOutboxDispatcherTest {

  private final OutboxDispatcher localLeg = mock(OutboxDispatcher.class);
  private final KafkaOutboxDispatcher externalLeg = mock(KafkaOutboxDispatcher.class);
  private final RoutingOutboxDispatcher router = new RoutingOutboxDispatcher(localLeg, externalLeg);

  @Test
  void aRowThatNamesADestinationGoesThereAndNotInProcess() {
    OutboxMessage message = message("ordering.events");

    router.dispatch(message);

    verify(externalLeg).dispatch(message, "ordering.events");
    verifyNoInteractions(localLeg);
  }

  @Test
  void aRowWithNoDestinationIsRepublishedInProcessAndNeverToTheBroker() {
    OutboxMessage message = message(null);

    router.dispatch(message);

    verify(localLeg).dispatch(message);
    verifyNoInteractions(externalLeg);
  }

  @Test
  void theStoredDestinationWinsOverWhateverTheCurrentRoutingTableWouldSay() {
    // The router is built without any routing table at all, which is the point: an event published
    // while it was @Externalized still reaches its topic after the annotation is gone from the
    // code.
    OutboxMessage message = message("legacy.events");

    router.dispatch(message);

    verify(externalLeg).dispatch(message, "legacy.events");
    verifyNoInteractions(localLeg);
  }

  private static OutboxMessage message(String destination) {
    return new OutboxMessage(
        "evt-1",
        "/ordering",
        "com.example.OrderPlaced",
        1,
        "{}",
        Instant.parse("2026-01-01T00:00:00Z"),
        "o-1",
        "__root__",
        "corr-1",
        "cause-1",
        destination);
  }
}
