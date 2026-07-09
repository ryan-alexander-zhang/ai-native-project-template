package com.acme.samples.s2.inventory.infrastructure.messaging;

import com.acme.samples.s2.inventory.api.StockResult;
import com.acme.samples.s2.inventory.application.stock.StockResultPublisher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Outbound adapter: writes the StockResult integration event to the transactional outbox. */
@Component
public class StockResultOutboxWriter implements StockResultPublisher {

    private final InventoryOutboxMapper outbox;
    private final ObjectMapper objectMapper;
    private final String topic;

    public StockResultOutboxWriter(InventoryOutboxMapper outbox, ObjectMapper objectMapper,
                                   @Value("${samples.stock-result-topic}") String topic) {
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.topic = topic;
    }

    @Override
    public void publish(StockResult result) {
        InventoryOutboxPo po = new InventoryOutboxPo();
        po.setTopic(topic);
        po.setMsgKey(result.orderId());
        po.setPayload(serialize(result));
        po.setSent(false);
        outbox.insert(po);
    }

    private String serialize(StockResult result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize StockResult", e);
        }
    }
}
