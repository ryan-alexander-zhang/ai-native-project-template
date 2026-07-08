package com.acme.samples.s2.ordering.application;

import com.acme.samples.s2.ordering.domain.Orders;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/** Read-side use case returning a DTO snapshot, so the adapter needs no domain types. */
@Service
public class FindOrderService {

    public record OrderSnapshot(String orderId, String status, long totalMinor, String currency) {}

    private final Orders orders;

    public FindOrderService(Orders orders) {
        this.orders = orders;
    }

    @Transactional(readOnly = true)
    public Optional<OrderSnapshot> byId(String id) {
        return orders.byId(id).map(o ->
                new OrderSnapshot(o.id(), o.status().name(), o.total().amountMinor(), o.total().currency()));
    }
}
