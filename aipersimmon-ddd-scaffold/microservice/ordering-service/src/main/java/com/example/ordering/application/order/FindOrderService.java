package com.example.ordering.application.order;

import com.example.ordering.domain.order.Order;
import com.example.ordering.domain.order.OrderId;
import com.example.ordering.domain.order.Orders;
import com.example.ordering.domain.shared.Money;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Reads an order as a snapshot. Kept minimal: it loads the aggregate and maps it.
 * A dedicated read model that bypasses the aggregate can be introduced later.
 */
@Service
public class FindOrderService {

    private final Orders orders;

    public FindOrderService(Orders orders) {
        this.orders = orders;
    }

    public Optional<OrderSnapshot> byId(String orderId) {
        return orders.findById(new OrderId(orderId)).map(FindOrderService::toSnapshot);
    }

    private static OrderSnapshot toSnapshot(Order order) {
        Money total = order.total();
        return new OrderSnapshot(
                order.id().value(),
                order.customerId().value(),
                order.status().name(),
                total.amountMinor(),
                total.currency());
    }
}
