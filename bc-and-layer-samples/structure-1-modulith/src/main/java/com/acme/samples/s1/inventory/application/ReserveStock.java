package com.acme.samples.s1.inventory.application;

import com.acme.samples.s1.inventory.domain.StockItem;
import com.acme.samples.s1.inventory.domain.StockItems;
import com.acme.samples.s1.inventory.infrastructure.ReservationMapper;
import com.acme.samples.s1.inventory.infrastructure.ReservationPo;
import com.acme.samples.s1.shared.OrderPlaced;
import com.acme.samples.s1.shared.SamplesProperties;
import com.acme.samples.s1.shared.StockResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumes OrderPlaced from Kafka, reserves stock (its own aggregate + schema),
 * and publishes the decision back to Kafka. Idempotent via the reservations
 * table (an inbox keyed by order id), so redelivery converges. (cross-BC consume
 * + message send + DB)
 */
@Component
public class ReserveStock {

    private static final Logger log = LoggerFactory.getLogger(ReserveStock.class);

    private final StockItems stock;
    private final ReservationMapper reservations;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper objectMapper;
    private final SamplesProperties properties;

    public ReserveStock(StockItems stock, ReservationMapper reservations, KafkaTemplate<String, String> kafka,
                        ObjectMapper objectMapper, SamplesProperties properties) {
        this.stock = stock;
        this.reservations = reservations;
        this.kafka = kafka;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @KafkaListener(topics = "${samples.order-placed-topic}", groupId = "s1-inventory")
    @Transactional
    public void onOrderPlaced(OrderPlaced event) throws Exception {
        ReservationPo existing = reservations.selectById(event.orderId());
        if (existing != null) { // inbox: already processed, republish stored outcome
            publish(existing.getOrderId(), existing.getSku(), "RESERVED".equals(existing.getOutcome()), "replay");
            return;
        }

        boolean ok = true;
        for (OrderPlaced.Line line : event.lines()) {
            StockItem item = stock.bySku(line.sku()).orElse(null);
            if (item == null || !item.canReserve(line.qty())) { ok = false; break; }
        }
        if (ok) {
            for (OrderPlaced.Line line : event.lines()) {
                stock.decrement(line.sku(), line.qty());
            }
        }

        String sku = event.lines().get(0).sku();
        int qty = event.lines().stream().mapToInt(OrderPlaced.Line::qty).sum();
        ReservationPo po = new ReservationPo();
        po.setOrderId(event.orderId());
        po.setSku(sku);
        po.setQty(qty);
        po.setOutcome(ok ? "RESERVED" : "REJECTED");
        reservations.insert(po);

        log.info("order {} stock {}", event.orderId(), po.getOutcome());
        publish(event.orderId(), sku, ok, ok ? "reserved" : "insufficient stock");
    }

    private void publish(String orderId, String sku, boolean reserved, String reason) throws Exception {
        StockResult result = new StockResult(orderId, sku, reserved, reason);
        kafka.send(properties.stockResultTopic(), orderId, objectMapper.writeValueAsString(result));
    }
}
