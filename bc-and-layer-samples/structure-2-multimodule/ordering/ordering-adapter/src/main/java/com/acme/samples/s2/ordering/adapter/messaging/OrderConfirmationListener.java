package com.acme.samples.s2.ordering.adapter.messaging;

import com.acme.samples.s2.inventory.api.StockResult;
import com.acme.samples.s2.ordering.application.order.ConfirmOrderCommand;
import com.acme.samples.s2.shared.CommandBus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Inbound messaging adapter: consumes Inventory's decision and dispatches a command. */
@Component
public class OrderConfirmationListener {

    private final CommandBus commandBus;
    private final ObjectMapper objectMapper;

    public OrderConfirmationListener(CommandBus commandBus, ObjectMapper objectMapper) {
        this.commandBus = commandBus;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "${samples.stock-result-topic}", groupId = "s2-ordering")
    public void onStockResult(String payload) throws Exception {
        StockResult result = objectMapper.readValue(payload, StockResult.class);
        commandBus.dispatch(new ConfirmOrderCommand(result.orderId(), result.reserved()));
    }
}
