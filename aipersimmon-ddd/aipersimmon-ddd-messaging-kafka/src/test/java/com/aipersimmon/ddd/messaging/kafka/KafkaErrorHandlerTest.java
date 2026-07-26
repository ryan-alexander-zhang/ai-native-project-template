package com.aipersimmon.ddd.messaging.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.aipersimmon.ddd.integration.MalformedIntegrationEventException;
import com.aipersimmon.ddd.integration.UnknownIntegrationEventException;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.listener.MessageListenerContainer;

/**
 * The error handler routes permanent failures straight to the recoverer (which, in production,
 * publishes to {@code <topic>.DLT}) without spending retries, while a transient failure is held for
 * redelivery instead of being recovered on the first failure — the behaviour Spring Kafka's
 * silent-skip default lacked. Exercises the handler directly, no broker.
 */
@ExtendWith(OutputCaptureExtension.class)
class KafkaErrorHandlerTest {

  private final KafkaMessagingProperties.Consumer props = consumerProperties();

  private static KafkaMessagingProperties.Consumer consumerProperties() {
    KafkaMessagingProperties.Consumer c = new KafkaMessagingProperties.Consumer();
    c.getRetry().setMaxRetries(5);
    c.getRetry().setInitialIntervalMs(1);
    c.getRetry().setMaxIntervalMs(1);
    c.setSystemicBackoffIntervalMs(1); // tiny so the systemic-retry test does not actually wait
    return c;
  }

  private final List<ConsumerRecord<?, ?>> recovered = new ArrayList<>();
  private final DefaultErrorHandler handler =
      AipersimmonDddMessagingKafkaAutoConfiguration.buildErrorHandler(
          (record, exception) -> recovered.add(record), props);

  private final Consumer<?, ?> consumer = mock(Consumer.class);
  private final MessageListenerContainer container = mock(MessageListenerContainer.class);

  private static ConsumerRecord<String, String> record() {
    return new ConsumerRecord<>("orders", 0, 0L, "key", "{}");
  }

  @Test
  void anUnknownEventTypeIsRecoveredImmediatelyWithoutRetrying() {
    ConsumerRecord<String, String> record = record();

    handler.handleRemaining(
        new ListenerExecutionFailedException(
            "listener failed", new UnknownIntegrationEventException("com.example.Unknown", 1)),
        List.of(record),
        consumer,
        container);

    assertEquals(
        1,
        recovered.size(),
        "a permanent failure is dead-lettered on the first failure, not after 5 retries");
    assertEquals(record, recovered.get(0));
  }

  @Test
  void aRecordMissingARequiredAttributeIsRecoveredImmediatelyWithoutRetrying() {
    ConsumerRecord<String, String> record = record();

    handler.handleRemaining(
        new ListenerExecutionFailedException(
            "listener failed", new MalformedIntegrationEventException("missing ce_id")),
        List.of(record),
        consumer,
        container);

    assertEquals(
        1,
        recovered.size(),
        "a malformed record (missing required attribute) is dead-lettered at once");
  }

  @Test
  void aMalformedPayloadIsRecoveredImmediatelyWithoutRetrying() {
    handler.handleRemaining(
        new ListenerExecutionFailedException(
            "listener failed",
            new IllegalStateException("reconstruct failed", new StubJsonException())),
        List.of(record()),
        consumer,
        container);

    assertEquals(
        1,
        recovered.size(),
        "a permanent (deserialization) failure is dead-lettered at once, cause chain traversed");
  }

  @Test
  void aTransientFailureIsNotRecoveredOnTheFirstFailure() {
    // The handler signals "held for redelivery, not yet recovered" by throwing
    // (RecordInRetryException) — the container catches it and redelivers after backoff.
    // The point of the assertion is that the recoverer below was NOT invoked.
    assertThrows(
        RuntimeException.class,
        () ->
            handler.handleRemaining(
                new ListenerExecutionFailedException(
                    "listener failed", new IllegalStateException("broker hiccup")),
                List.of(record()),
                consumer,
                container));

    assertEquals(
        0, recovered.size(), "a transient failure is retried, not dead-lettered immediately");
  }

  @Test
  void aSystemicInfrastructureFailureIsRetriedForeverAndNeverDeadLettered() {
    ConsumerRecord<String, String> record = record();
    ListenerExecutionFailedException failure =
        new ListenerExecutionFailedException(
            "listener failed", new DataAccessResourceFailureException("database is down"));

    // Redeliver far more than maxRetries (5): a bounded/ambiguous failure would have been
    // dead-lettered by now, but a systemic one is retried indefinitely and never recovered.
    for (int attempt = 0; attempt < 12; attempt++) {
      try {
        handler.handleRemaining(failure, List.of(record), consumer, container);
      } catch (RuntimeException heldForRedelivery) {
        // expected: the record is held, not recovered
      }
    }

    assertEquals(
        0,
        recovered.size(),
        "a systemic (infrastructure) failure is retried forever, never dead-lettered");
  }

  /**
   * The systemic path is the only one that never reaches the dead-letter recoverer, so it is the
   * only one whose failures would otherwise go unreported. Without this the sole output is Spring
   * Kafka's "Record in retry and not yet recovered" INFO, which names neither record nor cause
   * (issue-00057).
   */
  @Test
  void aSystemicStallIsReportedWithTheRecordAndTheCause(CapturedOutput output) {
    ConsumerRecord<String, String> record = new ConsumerRecord<>("orders", 3, 42L, "key", "{}");
    record.headers().add(IntegrationEventHeaders.ID, "evt-7".getBytes(StandardCharsets.UTF_8));
    ListenerExecutionFailedException failure =
        new ListenerExecutionFailedException(
            "listener failed", new DataAccessResourceFailureException("database is down"));

    for (int attempt = 0; attempt < 2; attempt++) {
      try {
        handler.handleRemaining(failure, List.of(record), consumer, container);
      } catch (RuntimeException heldForRedelivery) {
        // expected: the record is held for redelivery, not recovered
      }
    }

    assertTrue(output.getAll().contains("stalled at orders-3@42"), "names the stuck record");
    assertTrue(
        output.getAll().contains("evt-7"), "names the event id, so the message is traceable");
    assertTrue(output.getAll().contains("database is down"), "names the cause");
    assertTrue(
        output.getAll().contains("still stalled at orders-3@42"),
        "and keeps reporting, so the stall does not fall silent after the first delivery");
  }

  /** An ambiguous failure ends at the DLT with the recoverer's own ERROR; no stall report. */
  @Test
  void anAmbiguousFailureIsNotReportedAsAStall(CapturedOutput output) {
    assertThrows(
        RuntimeException.class,
        () ->
            handler.handleRemaining(
                new ListenerExecutionFailedException(
                    "listener failed", new IllegalStateException("broker hiccup")),
                List.of(record()),
                consumer,
                container));

    assertFalse(output.getAll().contains("stalled at"), "the bounded path is already visible");
  }

  /** A concrete JsonProcessingException so the not-retryable cause-chain check has one to find. */
  static final class StubJsonException extends JsonProcessingException {
    StubJsonException() {
      super("malformed");
    }
  }
}
