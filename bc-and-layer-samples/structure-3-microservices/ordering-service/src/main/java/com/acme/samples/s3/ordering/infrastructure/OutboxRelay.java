package com.acme.samples.s3.ordering.infrastructure;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class OutboxRelay {

    private final OutboxMapper outbox;
    private final KafkaTemplate<String, String> kafka;

    public OutboxRelay(OutboxMapper outbox, KafkaTemplate<String, String> kafka) {
        this.outbox = outbox;
        this.kafka = kafka;
    }

    @Scheduled(fixedDelay = 1000)
    public void flush() {
        List<OutboxPo> pending = outbox.selectList(new QueryWrapper<OutboxPo>().eq("sent", false).orderByAsc("id"));
        for (OutboxPo po : pending) {
            kafka.send(po.getTopic(), po.getMsgKey(), po.getPayload());
            po.setSent(true);
            outbox.updateById(po);
        }
    }
}
