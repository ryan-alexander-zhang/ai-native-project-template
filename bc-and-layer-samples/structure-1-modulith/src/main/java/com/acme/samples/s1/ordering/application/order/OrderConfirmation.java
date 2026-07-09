package com.acme.samples.s1.ordering.application.order;

import com.acme.samples.s1.ordering.domain.order.OrderStatus;
import com.acme.samples.s1.ordering.domain.order.Orders;
import com.acme.samples.s1.shared.StockResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes Inventory's decision from Kafka and drives the order state machine:
 * reserved -> CONFIRMED, otherwise -> CANCELLED. (cross-BC, message consume)
 *
 * <p>The payload is deserialized into {@link StockResult} by the Kafka message
 * converter contributed by Spring Modulith's Kafka support.
 */
@Component
public class OrderConfirmation {

    private static final Logger log = LoggerFactory.getLogger(OrderConfirmation.class);

    private final Orders orders;

    public OrderConfirmation(Orders orders) {
        this.orders = orders;
    }

    @KafkaListener(topics = "${samples.stock-result-topic}", groupId = "s1-ordering")
    public void onStockResult(StockResult result) {
        OrderStatus target = result.reserved() ? OrderStatus.CONFIRMED : OrderStatus.CANCELLED;
        orders.updateStatus(result.orderId(), target);
        log.info("order {} -> {} ({})", result.orderId(), target, result.reason());
    }
}
