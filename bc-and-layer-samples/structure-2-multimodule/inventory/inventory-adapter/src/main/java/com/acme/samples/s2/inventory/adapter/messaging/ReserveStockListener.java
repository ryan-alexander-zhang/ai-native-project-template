package com.acme.samples.s2.inventory.adapter.messaging;

import com.acme.samples.s2.inventory.api.StockResult;
import com.acme.samples.s2.inventory.application.ReserveStockService;
import com.acme.samples.s2.ordering.api.OrderPlaced;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Inbound messaging adapter: consumes OrderPlaced, reserves via the application
 * service, then publishes the StockResult back to Kafka.
 */
@Component
public class ReserveStockListener {

    private final ReserveStockService reserveStock;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper objectMapper;
    private final String stockResultTopic;

    public ReserveStockListener(ReserveStockService reserveStock, KafkaTemplate<String, String> kafka,
                                ObjectMapper objectMapper,
                                @Value("${samples.stock-result-topic}") String stockResultTopic) {
        this.reserveStock = reserveStock;
        this.kafka = kafka;
        this.objectMapper = objectMapper;
        this.stockResultTopic = stockResultTopic;
    }

    @KafkaListener(topics = "${samples.order-placed-topic}", groupId = "s2-inventory")
    public void onOrderPlaced(String payload) throws Exception {
        OrderPlaced event = objectMapper.readValue(payload, OrderPlaced.class);
        StockResult result = reserveStock.reserve(event);
        kafka.send(stockResultTopic, result.orderId(), objectMapper.writeValueAsString(result));
    }
}
