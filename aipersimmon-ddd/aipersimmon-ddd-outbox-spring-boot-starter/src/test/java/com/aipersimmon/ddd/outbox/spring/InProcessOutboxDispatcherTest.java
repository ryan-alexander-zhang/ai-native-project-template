package com.aipersimmon.ddd.outbox.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.aipersimmon.ddd.inbox.Inbox;
import com.aipersimmon.ddd.integration.EventEnvelope;
import com.aipersimmon.ddd.integration.IntegrationEvent;
import com.aipersimmon.ddd.integration.RegistryIntegrationEventCatalog;
import com.aipersimmon.ddd.integration.RegistryIntegrationEventCatalog.Key;
import com.aipersimmon.ddd.integration.UnknownIntegrationEventException;
import com.aipersimmon.ddd.outbox.OutboxMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.transaction.support.TransactionOperations;

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
                "acme",
                "corr-1",
                "cause-1",
                null));

    assertEquals(1, published.size());
    PayloadApplicationEvent<?> event =
        assertInstanceOf(PayloadApplicationEvent.class, published.get(0));
    EventEnvelope<?> envelope = assertInstanceOf(EventEnvelope.class, event.getPayload());
    SampleEvent payload = assertInstanceOf(SampleEvent.class, envelope.payload());
    assertEquals("O-1", payload.orderId());
    assertEquals("/orders", envelope.source());
    assertEquals("O-1", envelope.subject());
    assertEquals("acme", envelope.tenantId());
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
                        "__root__",
                        "corr-1",
                        null,
                        null)));
  }

  // --- redelivery dedup: the same handler must see the same guarantee whether the
  // envelope arrives over Kafka or from the local relay. The Kafka bridge checks the inbox inside
  // the consuming transaction; with an Inbox supplied, this dispatcher does the very same — same
  // key pair, and the check shares one transaction with the handlers so a failed delivery rolls
  // the dedup record back and the retry is not mistaken for a duplicate.

  private OutboxMessage sampleMessage(String eventId) {
    return new OutboxMessage(
        eventId,
        "/orders",
        TYPE,
        1,
        "{\"orderId\":\"O-1\"}",
        Instant.EPOCH,
        "O-1",
        "acme",
        "corr-1",
        "cause-1",
        null);
  }

  @Test
  void aRedeliveredMessageIsNotPublishedAgainWhenAnInboxIsPresent() {
    List<Object> published = new ArrayList<>();
    RecordingInbox inbox = new RecordingInbox();
    RegistryIntegrationEventCatalog catalog =
        new RegistryIntegrationEventCatalog(Map.of(new Key(TYPE, 1), SampleEvent.class));
    InProcessOutboxDispatcher dispatcher =
        new InProcessOutboxDispatcher(
            published::add,
            new ObjectMapper(),
            catalog,
            inbox,
            TransactionOperations.withoutTransaction());

    dispatcher.dispatch(sampleMessage("evt-1"));
    dispatcher.dispatch(sampleMessage("evt-1"));

    assertEquals(1, published.size(), "the redelivery must be absorbed by the inbox");
    assertEquals(1, inbox.recorded.size());
  }

  @Test
  void theInboxCheckAndThePublishShareOneTransaction() {
    List<Object> published = new ArrayList<>();
    RecordingInbox inbox = new RecordingInbox();
    RegistryIntegrationEventCatalog catalog =
        new RegistryIntegrationEventCatalog(Map.of(new Key(TYPE, 1), SampleEvent.class));
    CountingTransactions transactions = new CountingTransactions();
    InProcessOutboxDispatcher dispatcher =
        new InProcessOutboxDispatcher(
            event -> {
              assertEquals(
                  1, transactions.open, "the publish must run inside the dedup transaction");
              published.add(event);
            },
            new ObjectMapper(),
            catalog,
            inbox,
            transactions);

    dispatcher.dispatch(sampleMessage("evt-1"));

    assertEquals(1, published.size());
  }

  /** Records the (source, messageKey) pairs it has seen, like a real inbox but in memory. */
  private static final class RecordingInbox implements Inbox {
    private final Set<String> recorded = new HashSet<>();

    @Override
    public boolean alreadyProcessed(String source, String messageKey) {
      return !recorded.add(source + "|" + messageKey);
    }
  }

  /** Counts nesting so a test can assert code ran inside the transaction callback. */
  private static final class CountingTransactions implements TransactionOperations {
    private int open;

    @Override
    public <T> T execute(org.springframework.transaction.support.TransactionCallback<T> action) {
      open++;
      try {
        return action.doInTransaction(
            new org.springframework.transaction.support.SimpleTransactionStatus());
      } finally {
        open--;
      }
    }
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
                        "__root__",
                        "corr-1",
                        null,
                        null)));
  }
}
