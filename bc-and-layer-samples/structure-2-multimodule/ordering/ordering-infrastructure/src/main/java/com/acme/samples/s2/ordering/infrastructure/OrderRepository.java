package com.acme.samples.s2.ordering.infrastructure;

import com.acme.samples.s2.ordering.domain.Order;
import com.acme.samples.s2.ordering.domain.OrderLine;
import com.acme.samples.s2.ordering.domain.OrderStatus;
import com.acme.samples.s2.ordering.domain.Orders;
import com.acme.samples.s2.shared.Money;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class OrderRepository implements Orders {

    private final OrderMapper orderMapper;
    private final OrderLineMapper lineMapper;

    public OrderRepository(OrderMapper orderMapper, OrderLineMapper lineMapper) {
        this.orderMapper = orderMapper;
        this.lineMapper = lineMapper;
    }

    @Override
    public void save(Order order) {
        OrderPo po = new OrderPo();
        po.setId(order.id());
        po.setCustomerId(order.customerId());
        po.setStatus(order.status().name());
        po.setTotalMinor(order.total().amountMinor());
        po.setCurrency(order.total().currency());
        po.setCreatedAt(OffsetDateTime.now());
        orderMapper.insert(po);
        for (OrderLine line : order.lines()) {
            OrderLinePo lp = new OrderLinePo();
            lp.setOrderId(order.id());
            lp.setSku(line.sku());
            lp.setQty(line.qty());
            lp.setUnitPriceMinor(line.unitPriceMinor());
            lineMapper.insert(lp);
        }
    }

    @Override
    public Optional<Order> byId(String id) {
        OrderPo po = orderMapper.selectById(id);
        if (po == null) return Optional.empty();
        List<OrderLine> lines = lineMapper.selectList(new QueryWrapper<OrderLinePo>().eq("order_id", id))
                .stream().map(l -> new OrderLine(l.getSku(), l.getQty(), l.getUnitPriceMinor())).toList();
        return Optional.of(Order.rehydrate(po.getId(), po.getCustomerId(), lines,
                new Money(po.getTotalMinor(), po.getCurrency()), OrderStatus.valueOf(po.getStatus())));
    }

    @Override
    public void updateStatus(String id, OrderStatus status) {
        OrderPo po = orderMapper.selectById(id);
        if (po == null) return;
        po.setStatus(status.name());
        orderMapper.updateById(po);
    }
}
