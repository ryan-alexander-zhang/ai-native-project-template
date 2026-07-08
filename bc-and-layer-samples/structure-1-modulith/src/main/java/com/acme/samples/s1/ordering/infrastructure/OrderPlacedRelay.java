package com.acme.samples.s1.ordering.infrastructure;

import com.acme.samples.s1.shared.OrderPlaced;
import com.acme.samples.s1.shared.SamplesProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Outbound relay: turns the in-process OrderPlaced domain event into a Kafka
 * integration message. Because this is an {@code @ApplicationModuleListener}, the
 * publication is recorded in the Spring Modulith event registry (a transactional
 * outbox) — if the Kafka send fails, the publication stays incomplete and is
 * retried, so the event is never silently lost.
 */
@Component
public class OrderPlacedRelay {

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper objectMapper;
    private final SamplesProperties properties;

    public OrderPlacedRelay(KafkaTemplate<String, String> kafka, ObjectMapper objectMapper, SamplesProperties properties) {
        this.kafka = kafka;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @ApplicationModuleListener
    public void on(OrderPlaced event) throws Exception {
        kafka.send(properties.orderPlacedTopic(), event.orderId(), objectMapper.writeValueAsString(event));
    }
}
