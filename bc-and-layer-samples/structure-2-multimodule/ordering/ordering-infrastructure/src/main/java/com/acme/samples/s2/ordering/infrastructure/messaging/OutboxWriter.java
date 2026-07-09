package com.acme.samples.s2.ordering.infrastructure.messaging;

import com.acme.samples.s2.ordering.api.IntegrationEvent;
import com.acme.samples.s2.ordering.api.OrderCancelled;
import com.acme.samples.s2.ordering.api.OrderPlaced;
import com.acme.samples.s2.ordering.application.order.IntegrationEvents;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Outbound adapter: writes an integration event to the transactional outbox,
 * routing each event type to its topic (the outbox {@code topic} column is built
 * for this). The relay sends it to Kafka after commit.
 */
@Component
public class OutboxWriter implements IntegrationEvents {

    private final OutboxMapper outbox;
    private final ObjectMapper objectMapper;
    private final String orderPlacedTopic;
    private final String orderCancelledTopic;

    public OutboxWriter(OutboxMapper outbox, ObjectMapper objectMapper,
                        @Value("${samples.order-placed-topic}") String orderPlacedTopic,
                        @Value("${samples.order-cancelled-topic}") String orderCancelledTopic) {
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.orderPlacedTopic = orderPlacedTopic;
        this.orderCancelledTopic = orderCancelledTopic;
    }

    @Override
    public void publish(IntegrationEvent event) {
        String topic;
        String key;
        if (event instanceof OrderPlaced e) {
            topic = orderPlacedTopic;
            key = e.orderId();
        } else if (event instanceof OrderCancelled e) {
            topic = orderCancelledTopic;
            key = e.orderId();
        } else {
            throw new IllegalArgumentException("no topic mapping for " + event.getClass().getName());
        }

        OutboxPo po = new OutboxPo();
        po.setTopic(topic);
        po.setMsgKey(key);
        po.setPayload(serialize(event));
        po.setSent(false);
        outbox.insert(po);
    }

    private String serialize(IntegrationEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize " + event.getClass().getSimpleName(), e);
        }
    }
}
