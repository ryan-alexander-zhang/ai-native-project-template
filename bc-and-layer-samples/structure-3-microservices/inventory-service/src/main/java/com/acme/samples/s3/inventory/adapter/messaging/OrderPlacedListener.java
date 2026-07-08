package com.acme.samples.s3.inventory.adapter.messaging;

import com.acme.samples.s3.inventory.app.ReserveStockService;
import com.acme.samples.s3.inventory.client.OrderPlaced;
import com.acme.samples.s3.inventory.client.StockResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/** Consumes OrderPlaced from Kafka, reserves, and publishes StockResult back. */
@Component
public class OrderPlacedListener {

    private final ReserveStockService reserveStock;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper objectMapper;
    private final String stockResultTopic;

    public OrderPlacedListener(ReserveStockService reserveStock, KafkaTemplate<String, String> kafka,
                               ObjectMapper objectMapper,
                               @Value("${samples.stock-result-topic}") String stockResultTopic) {
        this.reserveStock = reserveStock;
        this.kafka = kafka;
        this.objectMapper = objectMapper;
        this.stockResultTopic = stockResultTopic;
    }

    @KafkaListener(topics = "${samples.order-placed-topic}", groupId = "s3-inventory")
    public void onOrderPlaced(String payload) throws Exception {
        OrderPlaced event = objectMapper.readValue(payload, OrderPlaced.class);
        StockResult result = reserveStock.reserve(event);
        kafka.send(stockResultTopic, result.orderId(), objectMapper.writeValueAsString(result));
    }
}
