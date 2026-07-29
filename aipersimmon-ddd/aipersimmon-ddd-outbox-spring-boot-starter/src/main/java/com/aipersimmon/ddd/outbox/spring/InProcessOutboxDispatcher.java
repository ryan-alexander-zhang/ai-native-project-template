package com.aipersimmon.ddd.outbox.spring;

import com.aipersimmon.ddd.integration.EventEnvelope;
import com.aipersimmon.ddd.integration.IntegrationEvent;
import com.aipersimmon.ddd.integration.IntegrationEventCatalog;
import com.aipersimmon.ddd.integration.UnknownIntegrationEventException;
import com.aipersimmon.ddd.outbox.OutboxDispatcher;
import com.aipersimmon.ddd.outbox.OutboxMessage;
import com.aipersimmon.ddd.tenancy.TenantContext;
import com.aipersimmon.ddd.tenancy.Tenants;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.core.ResolvableType;

/**
 * Dispatches outbox messages back into the same process: it reconstructs the integration event and
 * its {@link EventEnvelope} (payload plus metadata) from the stored row and republishes the
 * envelope through Spring's {@link ApplicationEventPublisher}, so in-process consumers with
 * {@code @EventListener} handlers for {@code EventEnvelope<TheEvent>} receive it with the full
 * metadata (event id, correlation, causation) intact.
 *
 * <p>This turns the outbox into an in-process asynchronous transport: the producer commits fast
 * (only the outbox row, in its transaction), and the scheduled relay delivers to local handlers
 * later. Because delivery is at-least-once, those handlers should be made idempotent (see the
 * inbox).
 */
public class InProcessOutboxDispatcher implements OutboxDispatcher {

  private final ApplicationEventPublisher publisher;
  private final ObjectMapper objectMapper;
  private final IntegrationEventCatalog catalog;

  public InProcessOutboxDispatcher(
      ApplicationEventPublisher publisher,
      ObjectMapper objectMapper,
      IntegrationEventCatalog catalog) {
    this.publisher = publisher;
    this.objectMapper = objectMapper;
    this.catalog = catalog;
  }

  /**
   * In-process delivery is real delivery, but it stops at the JVM boundary: an
   * {@code @Externalized} event republished here never reaches the broker it names. Saying so lets
   * the auto-configuration refuse to start such a deployment instead of letting it archive those
   * events as sent.
   */
  @Override
  public boolean reachesExternalTargets() {
    return false;
  }

  @Override
  public void dispatch(OutboxMessage message) {
    EventEnvelope<IntegrationEvent> envelope = reconstruct(message);
    // Carry the payload's concrete type so listeners typed EventEnvelope<TheEvent>
    // match despite erasure.
    ResolvableType type =
        ResolvableType.forClassWithGenerics(EventEnvelope.class, envelope.payload().getClass());
    // Bind the message's tenant around the delivery, exactly as the Kafka consumer bridge does:
    // handlers run on the relay's scheduler thread, which carries no binding of its own, and a
    // handler that reads or writes tenant-scoped data must see the same tenant either way. Without
    // this, the same handler receiving the same envelope would be tenant-scoped over Kafka and
    // tenant-less in-process.
    TenantContext.runAs(
        Tenants.fromValue(message.tenantId()),
        () -> publisher.publishEvent(new PayloadApplicationEvent<>(this, envelope, type)));
  }

  private EventEnvelope<IntegrationEvent> reconstruct(OutboxMessage message) {
    Class<? extends IntegrationEvent> type =
        catalog
            .lookup(message.type(), message.version())
            .orElseThrow(
                () -> new UnknownIntegrationEventException(message.type(), message.version()));
    try {
      IntegrationEvent payload = objectMapper.readValue(message.payload(), type);
      return new EventEnvelope<>(
          message.eventId(),
          message.source(),
          message.type(),
          message.version(),
          message.occurredAt(),
          message.subject(),
          message.tenantId(),
          message.correlationId(),
          message.causationId(),
          payload);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(
          "failed to reconstruct outbox message "
              + message.eventId()
              + " of type "
              + message.type(),
          e);
    }
  }
}
