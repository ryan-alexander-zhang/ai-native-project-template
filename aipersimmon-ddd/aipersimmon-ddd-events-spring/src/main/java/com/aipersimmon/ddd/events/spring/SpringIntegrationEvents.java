package com.aipersimmon.ddd.events.spring;

import com.aipersimmon.ddd.application.IntegrationEvents;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.integration.EventEnvelope;
import com.aipersimmon.ddd.integration.IntegrationEvent;
import java.time.Clock;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.core.ResolvableType;

/**
 * Publishes integration events in process through Spring's
 * {@link ApplicationEventPublisher} — the synchronous, same-thread,
 * same-transaction transport for a modular monolith where producer and consumer
 * share one deployable. It wraps the event in an {@link EventEnvelope} carrying the
 * minted event id, timestamp, and the causal chain from the emitting command's
 * {@link CommandContext}, and publishes the envelope so consumers register
 * {@code @EventListener} handlers for {@code EventEnvelope<TheEvent>} and receive the
 * full metadata — the same shape they would get from the outbox or a broker.
 *
 * <p>This is the "in-process synchronous" integration transport. For reliable
 * delivery decoupled from the producer's transaction (or across processes), use
 * the outbox instead.
 */
public class SpringIntegrationEvents implements IntegrationEvents {

    private final ApplicationEventPublisher publisher;
    private final Clock clock;
    private final String source;

    public SpringIntegrationEvents(ApplicationEventPublisher publisher, String source) {
        this(publisher, Clock.systemUTC(), source);
    }

    public SpringIntegrationEvents(ApplicationEventPublisher publisher, Clock clock, String source) {
        this.publisher = publisher;
        this.clock = clock;
        this.source = source;
    }

    @Override
    public void publish(IntegrationEvent event, CommandContext context) {
        // A brand-new event caused by the command described by context: mint a fresh event
        // id and record the command (context.messageId()) as the cause.
        publish(event, UUID.randomUUID().toString(), context.correlationId(), context.messageId());
    }

    @Override
    public void publishAs(IntegrationEvent event, CommandContext context) {
        // A staged effect replayed by the durable relay: stamp the persisted identity verbatim —
        // event id = the effect id (context.messageId()), cause = context.causationId() — so a
        // redelivery reaches in-process listeners under the same event id and an inbox dedupes it.
        publish(event, context.messageId(), context.correlationId(), context.causationId());
    }

    private void publish(IntegrationEvent event, String eventId, String correlationId, String causationId) {
        EventEnvelope<IntegrationEvent> envelope = new EventEnvelope<>(
                eventId,
                source,
                IntegrationEvent.eventTypeOf(event.getClass()),
                IntegrationEvent.eventVersionOf(event.getClass()),
                clock.instant(),
                event.subject(),
                correlationId,
                causationId,
                event);
        // Carry the payload's concrete type so listeners typed EventEnvelope<TheEvent>
        // match despite erasure.
        ResolvableType type = ResolvableType.forClassWithGenerics(
                EventEnvelope.class, event.getClass());
        publisher.publishEvent(new PayloadApplicationEvent<>(this, envelope, type));
    }
}
