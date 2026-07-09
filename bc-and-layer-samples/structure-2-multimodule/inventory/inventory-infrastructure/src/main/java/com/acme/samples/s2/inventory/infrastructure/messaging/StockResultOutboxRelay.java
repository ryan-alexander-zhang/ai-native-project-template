package com.acme.samples.s2.inventory.infrastructure.messaging;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Polls Inventory's outbox and publishes unsent StockResult events to Kafka, then
 * marks them sent. At-least-once; Ordering's inbox makes the consumer idempotent.
 * Mirrors Ordering's {@code OutboxRelay}.
 */
@Component
public class StockResultOutboxRelay {

    private final InventoryOutboxMapper outbox;
    private final KafkaTemplate<String, String> kafka;

    public StockResultOutboxRelay(InventoryOutboxMapper outbox, KafkaTemplate<String, String> kafka) {
        this.outbox = outbox;
        this.kafka = kafka;
    }

    @Scheduled(fixedDelay = 1000)
    public void flush() {
        List<InventoryOutboxPo> pending = outbox.selectList(
                new QueryWrapper<InventoryOutboxPo>().eq("sent", false).orderByAsc("id"));
        for (InventoryOutboxPo po : pending) {
            kafka.send(po.getTopic(), po.getMsgKey(), po.getPayload());
            po.setSent(true);
            outbox.updateById(po);
        }
    }
}
