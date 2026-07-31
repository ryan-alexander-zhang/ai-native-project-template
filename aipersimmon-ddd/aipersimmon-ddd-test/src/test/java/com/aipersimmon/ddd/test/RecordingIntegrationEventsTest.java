package com.aipersimmon.ddd.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.integration.EventEnvelope;
import com.aipersimmon.ddd.integration.EventType;
import com.aipersimmon.ddd.integration.IntegrationEvent;
import com.aipersimmon.ddd.tenancy.Tenants;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The envelope rules the recording publisher must keep: it wraps exactly what the real transports
 * wrap, reading the {@code @EventType} contract through the framework's own readers — so a test
 * asserts the produced wire, and an unannotated event fails here the way it would fail in
 * production (issue-00140).
 */
class RecordingIntegrationEventsTest {

  @EventType(name = "sample.order-placed", version = 2)
  record OrderPlaced(String orderId) implements IntegrationEvent {
    @Override
    public String subject() {
      return orderId;
    }
  }

  @EventType(name = "sample.order-shipped", version = 1, source = "/ordering")
  record OrderShipped(String orderId) implements IntegrationEvent {
    @Override
    public String subject() {
      return orderId;
    }
  }

  /** The failure a hand-rolled fake never produces: no {@code @EventType}, no publication. */
  record Unannotated(String id) implements IntegrationEvent {
    @Override
    public String subject() {
      return id;
    }
  }

  private static final CommandContext CONTEXT =
      new CommandContext(Tenants.of("acme"), "msg-1", "corr-1", "cause-0");

  private final RecordingIntegrationEvents events = new RecordingIntegrationEvents();

  @Test
  void publishWrapsTheFullEnvelopeTheWayTheRealTransportsDo() {
    events.publish(new OrderPlaced("order-1"), CONTEXT);

    EventEnvelope<IntegrationEvent> envelope = events.envelopes().get(0);
    assertEquals("sample.order-placed", envelope.type());
    assertEquals(2, envelope.version());
    assertEquals(RecordingIntegrationEvents.DEFAULT_SOURCE, envelope.source());
    assertEquals("order-1", envelope.subject());
    assertEquals("acme", envelope.tenantId());
    assertEquals("corr-1", envelope.correlationId());
    assertEquals("msg-1", envelope.causationId(), "the emitting command is the cause");
    assertEquals("evt-1", envelope.eventId(), "a fresh id is minted for a new event");
    assertEquals(Instant.parse("2026-01-01T00:00:00Z"), envelope.occurredAt());
  }

  @Test
  void theContractsOwnSourceWinsOverTheDeploymentDefault() {
    events.publish(new OrderShipped("order-1"), CONTEXT);

    assertEquals("/ordering", events.envelopes().get(0).source());
  }

  @Test
  void publishAsStampsThePersistedIdentityVerbatim() {
    events.publishAs(new OrderPlaced("order-1"), CONTEXT);

    EventEnvelope<IntegrationEvent> envelope = events.envelopes().get(0);
    assertEquals("msg-1", envelope.eventId(), "the effect's persisted id, not a fresh one");
    assertEquals("cause-0", envelope.causationId(), "the persisted causal chain, untouched");
  }

  @Test
  void anEventWithoutItsContractAnnotationIsRefused() {
    assertThrows(
        IllegalStateException.class, () -> events.publish(new Unannotated("x-1"), CONTEXT));
  }

  @Test
  void payloadsCanBeReadBackByType() {
    events.publish(new OrderPlaced("order-1"), CONTEXT);
    events.publish(new OrderShipped("order-1"), CONTEXT);

    assertEquals(1, events.eventsOf(OrderShipped.class).size());
    assertEquals(2, events.events().size());
  }
}
