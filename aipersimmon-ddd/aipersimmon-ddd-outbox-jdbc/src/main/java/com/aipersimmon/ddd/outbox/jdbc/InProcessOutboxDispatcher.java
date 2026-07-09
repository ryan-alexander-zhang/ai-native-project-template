package com.aipersimmon.ddd.outbox.jdbc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Dispatches outbox messages back into the same process: it reconstructs the
 * integration event from the stored type and payload and republishes it through
 * Spring's {@link ApplicationEventPublisher}, so in-process consumers with
 * {@code @EventListener} handlers receive it.
 *
 * <p>This turns the outbox into an in-process asynchronous transport: the producer
 * commits fast (only the outbox row, in its transaction), and the scheduled relay
 * delivers to local handlers later. Because delivery is at-least-once, those
 * handlers should be made idempotent (see the inbox).
 */
public class InProcessOutboxDispatcher implements OutboxDispatcher {

    private final ApplicationEventPublisher publisher;
    private final ObjectMapper objectMapper;

    public InProcessOutboxDispatcher(ApplicationEventPublisher publisher, ObjectMapper objectMapper) {
        this.publisher = publisher;
        this.objectMapper = objectMapper;
    }

    @Override
    public void dispatch(OutboxMessage message) {
        publisher.publishEvent(reconstruct(message));
    }

    private Object reconstruct(OutboxMessage message) {
        try {
            Class<?> type = Class.forName(message.type());
            return objectMapper.readValue(message.payload(), type);
        } catch (ClassNotFoundException | JsonProcessingException e) {
            throw new IllegalStateException(
                    "failed to reconstruct outbox message " + message.eventId()
                            + " of type " + message.type(), e);
        }
    }
}
