package com.aipersimmon.ddd.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.aipersimmon.ddd.integration.EventEnvelope;
import com.aipersimmon.ddd.integration.IntegrationEvent;
import com.aipersimmon.ddd.integration.RegistryIntegrationEventCatalog;
import com.aipersimmon.ddd.integration.RegistryIntegrationEventCatalog.Key;
import com.aipersimmon.ddd.integration.UnknownIntegrationEventException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.PayloadApplicationEvent;

/**
 * Unit-tests the in-process dispatcher's reconstruct-and-republish behavior without a Spring
 * context: it looks the stored {@code (type, version)} up in the catalog, rebuilds the {@link
 * EventEnvelope} from the stored metadata + payload, and hands it to the publisher; an unknown
 * {@code (type, version)} fails as {@link UnknownIntegrationEventException} (to be dead-lettered),
 * with no class-name fallback. The full wiring (writer -> relay -> dispatcher) is covered by each
 * storage starter's own test.
 */
class InProcessOutboxDispatcherTest {

  private static final String TYPE = "com.example.ordering.Sample";

  record SampleEvent(String orderId) implements IntegrationEvent {}

  private InProcessOutboxDispatcher dispatcher(ApplicationEventPublisher publisher) {
    RegistryIntegrationEventCatalog catalog =
        new RegistryIntegrationEventCatalog(Map.of(new Key(TYPE, 1), SampleEvent.class));
    return new InProcessOutboxDispatcher(publisher, new ObjectMapper(), catalog);
  }

  @Test
  void reconstructsStoredEventAndPublishesTheEnvelope() {
    List<Object> published = new ArrayList<>();

    dispatcher(published::add)
        .dispatch(
            new OutboxMessage(
                "evt-1",
                "/orders",
                TYPE,
                1,
                "{\"orderId\":\"O-1\"}",
                Instant.EPOCH,
                "O-1",
                "corr-1",
                "cause-1"));

    assertEquals(1, published.size());
    PayloadApplicationEvent<?> event =
        assertInstanceOf(PayloadApplicationEvent.class, published.get(0));
    EventEnvelope<?> envelope = assertInstanceOf(EventEnvelope.class, event.getPayload());
    SampleEvent payload = assertInstanceOf(SampleEvent.class, envelope.payload());
    assertEquals("O-1", payload.orderId());
    assertEquals("/orders", envelope.source());
    assertEquals("O-1", envelope.subject());
    assertEquals("corr-1", envelope.correlationId());
    assertEquals("cause-1", envelope.causationId());
  }

  @Test
  void failsWhenTypeIsUnknown() {
    assertThrows(
        UnknownIntegrationEventException.class,
        () ->
            dispatcher(event -> {})
                .dispatch(
                    new OutboxMessage(
                        "evt-2",
                        "/orders",
                        "com.example.DoesNotExist",
                        1,
                        "{}",
                        Instant.EPOCH,
                        null,
                        "corr-1",
                        null)));
  }

  @Test
  void failsWhenVersionIsUnknown() {
    // The type is registered, but only at version 1; a version-2 message is a miss.
    assertThrows(
        UnknownIntegrationEventException.class,
        () ->
            dispatcher(event -> {})
                .dispatch(
                    new OutboxMessage(
                        "evt-3",
                        "/orders",
                        TYPE,
                        2,
                        "{\"orderId\":\"O-1\"}",
                        Instant.EPOCH,
                        "O-1",
                        "corr-1",
                        null)));
  }
}
