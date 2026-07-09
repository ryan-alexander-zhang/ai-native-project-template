package com.acme.samples.s2.ordering.adapter.messaging;

import com.acme.samples.s2.inventory.api.StockResult;
import com.acme.samples.s2.ordering.application.order.ConfirmOrderService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Inbound messaging adapter: consumes Inventory's decision from Kafka. */
@Component
public class OrderConfirmationListener {

    private final ConfirmOrderService confirmOrder;
    private final ObjectMapper objectMapper;

    public OrderConfirmationListener(ConfirmOrderService confirmOrder, ObjectMapper objectMapper) {
        this.confirmOrder = confirmOrder;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "${samples.stock-result-topic}", groupId = "s2-ordering")
    public void onStockResult(String payload) throws Exception {
        StockResult result = objectMapper.readValue(payload, StockResult.class);
        confirmOrder.apply(result.orderId(), result.reserved());
    }
}
