package com.acme.samples.s3.ordering.infrastructure.messaging;

import com.acme.samples.s3.ordering.app.order.OrderPlacedPublisher;
import com.acme.samples.s3.ordering.client.OrderPlaced;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class OutboxWriter implements OrderPlacedPublisher {

    private final OutboxMapper outbox;
    private final ObjectMapper objectMapper;
    private final String topic;

    public OutboxWriter(OutboxMapper outbox, ObjectMapper objectMapper,
                        @Value("${samples.order-placed-topic}") String topic) {
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.topic = topic;
    }

    @Override
    public void publish(OrderPlaced event) {
        OutboxPo po = new OutboxPo();
        po.setTopic(topic);
        po.setMsgKey(event.orderId());
        po.setPayload(serialize(event));
        po.setSent(false);
        outbox.insert(po);
    }

    private String serialize(OrderPlaced event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize OrderPlaced", e);
        }
    }
}
