package com.aipersimmon.ddd.test;

import com.aipersimmon.ddd.application.IntegrationEvents;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.integration.EventEnvelope;
import com.aipersimmon.ddd.integration.IntegrationEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * An {@link IntegrationEvents} that records full {@link EventEnvelope}s instead of transporting
 * them — the official test double. A minimal hand-rolled fake collects bare payloads and thereby
 * asserts nothing about the wire: this one builds each envelope exactly the way {@code
 * SpringIntegrationEvents} and the outbox writer do, reusing the framework's own {@code @EventType}
 * readers, so a test sees — and an event class missing its {@code @EventType} annotation fails —
 * precisely as in production:
 *
 * <ul>
 *   <li>{@link #publish} mints a fresh event id, takes the source from the contract's own
 *       {@code @EventType(source=...)} falling back to this fake's deployment source, and records
 *       the command as the cause;
 *   <li>{@link #publishAs} stamps the persisted identity verbatim — event id = {@code
 *       context.messageId()}, cause = {@code context.causationId()} — so redelivery-dedup tests can
 *       assert one stable event id.
 * </ul>
 *
 * <p>Timestamps come from an injectable {@link Clock} (fixed by default) so envelope assertions are
 * deterministic. Instances are safe to share across the test's threads.
 */
public final class RecordingIntegrationEvents implements IntegrationEvents {

  /** The deployment-wide default source used when a contract does not declare its own. */
  public static final String DEFAULT_SOURCE = "/test";

  private static final Instant DEFAULT_NOW = Instant.parse("2026-01-01T00:00:00Z");

  private final String source;
  private final Clock clock;
  private final List<EventEnvelope<IntegrationEvent>> published = new CopyOnWriteArrayList<>();
  private final AtomicLong ids = new AtomicLong();

  public RecordingIntegrationEvents() {
    this(DEFAULT_SOURCE, Clock.fixed(DEFAULT_NOW, ZoneOffset.UTC));
  }

  public RecordingIntegrationEvents(String source, Clock clock) {
    this.source = source;
    this.clock = clock;
  }

  @Override
  public void publish(IntegrationEvent event, CommandContext context) {
    record(event, "evt-" + ids.incrementAndGet(), context, context.messageId());
  }

  @Override
  public void publishAs(IntegrationEvent event, CommandContext context) {
    record(event, context.messageId(), context, context.causationId());
  }

  private void record(
      IntegrationEvent event, String eventId, CommandContext context, String causationId) {
    published.add(
        new EventEnvelope<>(
            eventId,
            // The contract's own source wins over the deployment default — the same rule as the
            // durable writer and the in-process publisher, so a test pins the produced wire.
            IntegrationEvent.sourceOf(event.getClass()).orElse(source),
            IntegrationEvent.eventTypeOf(event.getClass()),
            IntegrationEvent.eventVersionOf(event.getClass()),
            clock.instant(),
            event.subject(),
            context.tenantId().value(),
            context.correlationId(),
            causationId,
            event));
  }

  /** Every published envelope in order — id, source, type, version, tenant, causal chain, all. */
  public List<EventEnvelope<IntegrationEvent>> envelopes() {
    return List.copyOf(published);
  }

  /** Just the payloads, in order, for tests that only care what was announced. */
  public List<IntegrationEvent> events() {
    return published.stream().map(EventEnvelope::payload).toList();
  }

  /** The published payloads of one type, in order. */
  @SuppressWarnings("unchecked")
  public <E extends IntegrationEvent> List<E> eventsOf(Class<E> eventType) {
    return published.stream()
        .map(EventEnvelope::payload)
        .filter(eventType::isInstance)
        .map(event -> (E) event)
        .toList();
  }

  /** Forget everything recorded so far. */
  public void reset() {
    published.clear();
  }
}
