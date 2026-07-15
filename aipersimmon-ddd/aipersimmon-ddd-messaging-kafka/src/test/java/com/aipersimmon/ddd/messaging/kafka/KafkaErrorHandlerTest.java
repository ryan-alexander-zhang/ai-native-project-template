package com.aipersimmon.ddd.messaging.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import com.aipersimmon.ddd.integration.UnknownIntegrationEventException;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.ArrayList;
import java.util.List;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.listener.MessageListenerContainer;

/**
 * The error handler routes permanent failures straight to the recoverer (which, in
 * production, publishes to {@code <topic>.DLT}) without spending retries, while a
 * transient failure is held for redelivery instead of being recovered on the first
 * failure — the behaviour Spring Kafka's silent-skip default lacked. Exercises the
 * handler directly, no broker.
 */
class KafkaErrorHandlerTest {

    private final KafkaMessagingProperties.Consumer.Retry retry = retry();

    private static KafkaMessagingProperties.Consumer.Retry retry() {
        KafkaMessagingProperties.Consumer.Retry r = new KafkaMessagingProperties.Consumer.Retry();
        r.setMaxRetries(5);
        r.setInitialIntervalMs(1);
        r.setMaxIntervalMs(1);
        return r;
    }

    private final List<ConsumerRecord<?, ?>> recovered = new ArrayList<>();
    private final DefaultErrorHandler handler =
            AipersimmonDddMessagingKafkaAutoConfiguration.buildErrorHandler(
                    (record, exception) -> recovered.add(record), retry);

    private final Consumer<?, ?> consumer = mock(Consumer.class);
    private final MessageListenerContainer container = mock(MessageListenerContainer.class);

    private static ConsumerRecord<String, String> record() {
        return new ConsumerRecord<>("orders", 0, 0L, "key", "{}");
    }

    @Test
    void anUnknownEventTypeIsRecoveredImmediatelyWithoutRetrying() {
        ConsumerRecord<String, String> record = record();

        handler.handleRemaining(
                new ListenerExecutionFailedException("listener failed",
                        new UnknownIntegrationEventException("com.example.Unknown", 1)),
                List.of(record), consumer, container);

        assertEquals(1, recovered.size(),
                "a permanent failure is dead-lettered on the first failure, not after 5 retries");
        assertEquals(record, recovered.get(0));
    }

    @Test
    void aMalformedPayloadIsRecoveredImmediatelyWithoutRetrying() {
        handler.handleRemaining(
                new ListenerExecutionFailedException("listener failed",
                        new IllegalStateException("reconstruct failed", new StubJsonException())),
                List.of(record()), consumer, container);

        assertEquals(1, recovered.size(),
                "a permanent (deserialization) failure is dead-lettered at once, cause chain traversed");
    }

    @Test
    void aTransientFailureIsNotRecoveredOnTheFirstFailure() {
        // The handler signals "held for redelivery, not yet recovered" by throwing
        // (RecordInRetryException) — the container catches it and redelivers after backoff.
        // The point of the assertion is that the recoverer below was NOT invoked.
        assertThrows(RuntimeException.class, () -> handler.handleRemaining(
                new ListenerExecutionFailedException("listener failed",
                        new IllegalStateException("broker hiccup")),
                List.of(record()), consumer, container));

        assertEquals(0, recovered.size(),
                "a transient failure is retried, not dead-lettered immediately");
    }

    /** A concrete JsonProcessingException so the not-retryable cause-chain check has one to find. */
    static final class StubJsonException extends JsonProcessingException {
        StubJsonException() {
            super("malformed");
        }
    }
}
