package com.aipersimmon.ddd.events.spring;

import com.aipersimmon.ddd.application.IntegrationEvents;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.integration.EventEnvelope;
import com.aipersimmon.ddd.integration.IntegrationEvent;
import java.time.Clock;
import java.util.function.Supplier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.core.ResolvableType;

/**
 * Publishes integration events in process through Spring's {@link ApplicationEventPublisher} — the
 * synchronous, same-thread, same-transaction transport for a modular monolith where producer and
 * consumer share one deployable. It wraps the event in an {@link EventEnvelope} carrying the minted
 * event id, timestamp, and the causal chain from the emitting command's {@link CommandContext}, and
 * publishes the envelope so consumers register {@code @EventListener} handlers for {@code
 * EventEnvelope<TheEvent>} and receive the full metadata — the same shape they would get from the
 * outbox or a broker.
 *
 * <p>This is the "in-process synchronous" integration transport. For reliable delivery decoupled
 * from the producer's transaction (or across processes), use the outbox instead.
 */
public class SpringIntegrationEvents implements IntegrationEvents {

  private final ApplicationEventPublisher publisher;
  private final Clock clock;
  private final String source;
  private final Supplier<String> idGenerator;

  /**
   * @param idGenerator supplies each brand-new event's id. Required: there is no defaulting
   *     overload, so a caller cannot accidentally fall back to a non-time-ordered id.
   *     Auto-configuration passes the {@link com.aipersimmon.ddd.core.id.IdGenerator} bean; tests
   *     pass a deterministic supplier.
   */
  public SpringIntegrationEvents(
      ApplicationEventPublisher publisher,
      Clock clock,
      String source,
      Supplier<String> idGenerator) {
    this.publisher = publisher;
    this.clock = clock;
    this.source = source;
    this.idGenerator = idGenerator;
  }

  @Override
  public void publish(IntegrationEvent event, CommandContext context) {
    // A brand-new event caused by the command described by context: mint a fresh event
    // id and record the command (context.messageId()) as the cause.
    publish(
        event,
        idGenerator.get(),
        context.tenantId().value(),
        context.correlationId(),
        context.messageId());
  }

  @Override
  public void publishAs(IntegrationEvent event, CommandContext context) {
    // A staged effect replayed by the durable relay: stamp the persisted identity verbatim —
    // event id = the effect id (context.messageId()), cause = context.causationId() — so a
    // redelivery reaches in-process listeners under the same event id and an inbox dedupes it.
    publish(
        event,
        context.messageId(),
        context.tenantId().value(),
        context.correlationId(),
        context.causationId());
  }

  private void publish(
      IntegrationEvent event,
      String eventId,
      String tenantId,
      String correlationId,
      String causationId) {
    EventEnvelope<IntegrationEvent> envelope =
        new EventEnvelope<>(
            eventId,
            // The contract's own source wins over the deployment-wide default, mirroring the
            // durable writer: the same event must claim the same producer on either publisher.
            IntegrationEvent.sourceOf(event.getClass()).orElse(source),
            IntegrationEvent.eventTypeOf(event.getClass()),
            IntegrationEvent.eventVersionOf(event.getClass()),
            clock.instant(),
            event.subject(),
            tenantId,
            correlationId,
            causationId,
            event);
    // Carry the payload's concrete type so listeners typed EventEnvelope<TheEvent>
    // match despite erasure.
    ResolvableType type =
        ResolvableType.forClassWithGenerics(EventEnvelope.class, event.getClass());
    publisher.publishEvent(new PayloadApplicationEvent<>(this, envelope, type));
  }
}
