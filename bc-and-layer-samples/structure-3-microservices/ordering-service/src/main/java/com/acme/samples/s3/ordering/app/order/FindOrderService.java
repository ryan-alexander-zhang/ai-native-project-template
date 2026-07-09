package com.acme.samples.s3.ordering.app.order;

import com.acme.samples.s3.ordering.domain.order.Orders;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class FindOrderService {

    public record OrderSnapshot(String orderId, String status, long totalMinor, String currency) {}

    private final Orders orders;

    public FindOrderService(Orders orders) { this.orders = orders; }

    @Transactional(readOnly = true)
    public Optional<OrderSnapshot> byId(String id) {
        return orders.byId(id).map(o ->
                new OrderSnapshot(o.id(), o.status().name(), o.total().amountMinor(), o.total().currency()));
    }
}
