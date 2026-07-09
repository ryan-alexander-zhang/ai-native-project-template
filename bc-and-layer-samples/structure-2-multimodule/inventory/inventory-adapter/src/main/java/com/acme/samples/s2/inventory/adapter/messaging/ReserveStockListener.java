package com.acme.samples.s2.inventory.adapter.messaging;

import com.acme.samples.s2.inventory.application.stock.ReserveStockService;
import com.acme.samples.s2.ordering.api.OrderPlaced;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Inbound messaging adapter: consumes OrderPlaced and delegates to the reservation
 * use case. The StockResult is emitted via the transactional outbox inside that
 * use case (not sent here), so the return leg is reliable (analysis-00005 §4).
 */
@Component
public class ReserveStockListener {

    private final ReserveStockService reserveStock;
    private final ObjectMapper objectMapper;

    public ReserveStockListener(ReserveStockService reserveStock, ObjectMapper objectMapper) {
        this.reserveStock = reserveStock;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "${samples.order-placed-topic}", groupId = "s2-inventory")
    public void onOrderPlaced(String payload) throws Exception {
        OrderPlaced event = objectMapper.readValue(payload, OrderPlaced.class);
        reserveStock.reserve(event);
    }
}
