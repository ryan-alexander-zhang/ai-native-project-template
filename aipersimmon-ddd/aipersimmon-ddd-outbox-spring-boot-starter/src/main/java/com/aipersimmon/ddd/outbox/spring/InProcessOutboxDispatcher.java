package com.aipersimmon.ddd.outbox.spring;

import com.aipersimmon.ddd.inbox.Inbox;
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
import java.util.Objects;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.core.ResolvableType;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Dispatches outbox messages back into the same process: it reconstructs the integration event and
 * its {@link EventEnvelope} (payload plus metadata) from the stored row and republishes the
 * envelope through Spring's {@link ApplicationEventPublisher}, so in-process consumers with
 * {@code @EventListener} handlers for {@code EventEnvelope<TheEvent>} receive it with the full
 * metadata (event id, correlation, causation) intact.
 *
 * <p>This turns the outbox into an in-process asynchronous transport: the producer commits fast
 * (only the outbox row, in its transaction), and the scheduled relay delivers to local handlers
 * later.
 *
 * <p>Delivery is at-least-once — the relay can crash between a successful dispatch and marking the
 * row sent, and then redelivers it. Given an {@link Inbox}, this dispatcher absorbs that redelivery
 * itself, exactly as the Kafka consumer bridge does for brokered delivery: same {@code (source,
 * id)} key pair, and the dedup check shares one transaction with the handlers it guards, so a
 * failed delivery rolls its inbox record back and the retry is not mistaken for a duplicate. The
 * same handler thereby sees the same guarantee whichever transport delivered the envelope. Without
 * an {@code Inbox}, redeliveries reach handlers again and every handler must tolerate its own
 * earlier success — the auto-configuration says so loudly at startup.
 */
public class InProcessOutboxDispatcher implements OutboxDispatcher {

  private final ApplicationEventPublisher publisher;
  private final ObjectMapper objectMapper;
  private final IntegrationEventCatalog catalog;
  private final Inbox inbox;
  private final TransactionOperations transactions;

  /** Without an inbox: redeliveries are the handlers' problem, see the class javadoc. */
  public InProcessOutboxDispatcher(
      ApplicationEventPublisher publisher,
      ObjectMapper objectMapper,
      IntegrationEventCatalog catalog) {
    this.publisher = publisher;
    this.objectMapper = objectMapper;
    this.catalog = catalog;
    this.inbox = null;
    this.transactions = null;
  }

  /**
   * With an inbox: redeliveries are absorbed here. The two extra collaborators come together on
   * purpose — an inbox consulted outside the handlers' transaction is worse than none, because a
   * delivery that records its inbox row and then fails would see its own retry dropped as a
   * duplicate. {@code transactions} is what makes the record and the side effects one fate.
   */
  public InProcessOutboxDispatcher(
      ApplicationEventPublisher publisher,
      ObjectMapper objectMapper,
      IntegrationEventCatalog catalog,
      Inbox inbox,
      TransactionOperations transactions) {
    this.publisher = publisher;
    this.objectMapper = objectMapper;
    this.catalog = catalog;
    this.inbox = Objects.requireNonNull(inbox, "inbox");
    this.transactions = Objects.requireNonNull(transactions, "transactions");
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
    // tenant-less in-process. The inbox check runs inside the binding for the same reason: the
    // dedup row is tenant-stamped data.
    TenantContext.runAs(
        Tenants.fromValue(message.tenantId()), () -> deliver(message, envelope, type));
  }

  private void deliver(
      OutboxMessage message, EventEnvelope<IntegrationEvent> envelope, ResolvableType type) {
    if (inbox == null) {
      publisher.publishEvent(new PayloadApplicationEvent<>(this, envelope, type));
      return;
    }
    // Mirror of the Kafka bridge's consuming method: dedup check and handler side effects in one
    // transaction. Keyed on the pair — an id is only unique within the source that minted it.
    transactions.executeWithoutResult(
        status -> {
          if (inbox.alreadyProcessed(message.source(), message.eventId())) {
            return;
          }
          publisher.publishEvent(new PayloadApplicationEvent<>(this, envelope, type));
        });
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
