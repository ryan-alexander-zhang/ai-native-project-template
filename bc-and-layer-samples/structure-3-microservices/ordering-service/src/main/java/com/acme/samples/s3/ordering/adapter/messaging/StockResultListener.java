package com.acme.samples.s3.ordering.adapter.messaging;

import com.acme.samples.s3.ordering.app.order.ConfirmOrderService;
import com.acme.samples.s3.ordering.client.StockResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Consumes inventory-service's StockResult from Kafka (cross-service, async). */
@Component
public class StockResultListener {

    private final ConfirmOrderService confirmOrder;
    private final ObjectMapper objectMapper;

    public StockResultListener(ConfirmOrderService confirmOrder, ObjectMapper objectMapper) {
        this.confirmOrder = confirmOrder;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "${samples.stock-result-topic}", groupId = "s3-ordering")
    public void onStockResult(String payload) throws Exception {
        StockResult result = objectMapper.readValue(payload, StockResult.class);
        confirmOrder.apply(result.orderId(), result.reserved());
    }
}
