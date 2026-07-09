package com.acme.samples.s2.ordering.infrastructure.readmodel;

import com.acme.samples.s2.ordering.application.order.OrderProjection;
import com.acme.samples.s2.ordering.application.order.OrderQueries;
import com.acme.samples.s2.ordering.application.order.OrderSnapshot;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Read-model adapter: implements both the write port {@link OrderProjection}
 * (event-driven upserts) and the read port {@link OrderQueries} (queries that
 * bypass the aggregate and its write repository). analysis-00005 §5.
 */
@Repository
public class OrderViewRepository implements OrderProjection, OrderQueries {

    private final OrderViewMapper mapper;

    public OrderViewRepository(OrderViewMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void placed(String orderId, long totalMinor, String currency) {
        OrderViewPo po = new OrderViewPo();
        po.setOrderId(orderId);
        po.setStatus("PENDING");
        po.setTotalMinor(totalMinor);
        po.setCurrency(currency);
        mapper.insert(po);
    }

    @Override
    public void statusChanged(String orderId, String status) {
        OrderViewPo po = mapper.selectById(orderId);
        if (po == null) return;
        po.setStatus(status);
        mapper.updateById(po);
    }

    @Override
    public Optional<OrderSnapshot> byId(String id) {
        OrderViewPo po = mapper.selectById(id);
        return po == null ? Optional.empty()
                : Optional.of(new OrderSnapshot(po.getOrderId(), po.getStatus(), po.getTotalMinor(), po.getCurrency()));
    }
}
