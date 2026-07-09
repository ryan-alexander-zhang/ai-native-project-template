package com.acme.samples.s2.ordering.infrastructure.scheduler;

import com.acme.samples.s2.ordering.application.order.CancelStaleOrderCommand;
import com.acme.samples.s2.ordering.infrastructure.persistence.order.OrderMapper;
import com.acme.samples.s2.ordering.infrastructure.persistence.order.OrderPo;
import com.acme.samples.s2.shared.CommandBus;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Saga liveness (analysis-00005 §八/G6): periodically finds PENDING orders older
 * than the deadline (their stock result never arrived) and dispatches a
 * {@link CancelStaleOrderCommand} to compensate. This is orthogonal to the outbox
 * (reliable per-hop delivery); it bounds the time a saga may stay unresolved.
 */
@Component
public class PendingOrderTimeoutScanner {

    private final OrderMapper orders;
    private final CommandBus commandBus;
    private final Duration timeout;

    public PendingOrderTimeoutScanner(OrderMapper orders, CommandBus commandBus,
                                      @Value("${samples.pending-timeout}") Duration timeout) {
        this.orders = orders;
        this.commandBus = commandBus;
        this.timeout = timeout;
    }

    @Scheduled(fixedDelayString = "${samples.pending-scan-interval-ms:5000}")
    public void scan() {
        OffsetDateTime cutoff = OffsetDateTime.now().minus(timeout);
        List<OrderPo> stale = orders.selectList(new QueryWrapper<OrderPo>()
                .eq("status", "PENDING")
                .lt("created_at", cutoff));
        for (OrderPo po : stale) {
            commandBus.dispatch(new CancelStaleOrderCommand(po.getId()));
        }
    }
}
