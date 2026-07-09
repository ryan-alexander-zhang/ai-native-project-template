package com.acme.samples.s2.inventory.adapter.messaging;

import com.acme.samples.s2.inventory.application.stock.ReleaseStockService;
import com.acme.samples.s2.ordering.api.OrderCancelled;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Inbound messaging adapter: consumes Ordering's OrderCancelled and releases any
 * stock reserved for that order (saga compensation, analysis-00005 §八/G6).
 */
@Component
public class OrderCancelledListener {

    private final ReleaseStockService releaseStock;
    private final ObjectMapper objectMapper;

    public OrderCancelledListener(ReleaseStockService releaseStock, ObjectMapper objectMapper) {
        this.releaseStock = releaseStock;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "${samples.order-cancelled-topic}", groupId = "s2-inventory")
    public void onOrderCancelled(String payload) throws Exception {
        OrderCancelled event = objectMapper.readValue(payload, OrderCancelled.class);
        releaseStock.release(event.orderId());
    }
}
