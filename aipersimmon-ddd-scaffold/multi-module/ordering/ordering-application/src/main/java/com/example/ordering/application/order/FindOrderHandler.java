package com.example.ordering.application.order;

import com.aipersimmon.ddd.application.UseCase;
import com.aipersimmon.ddd.cqrs.QueryHandler;
import com.example.ordering.domain.order.Order;
import com.example.ordering.domain.order.OrderId;
import com.example.ordering.domain.order.Orders;
import com.example.ordering.domain.shared.Money;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Answers {@link FindOrder} with an {@link OrderSnapshot}. Kept minimal: it loads
 * the aggregate and maps it. A dedicated read model that bypasses the aggregate can
 * be introduced later (see the CQRS read-model how-to).
 */
@Component
@UseCase
public class FindOrderHandler implements QueryHandler<FindOrder, Optional<OrderSnapshot>> {

    private final Orders orders;

    public FindOrderHandler(Orders orders) {
        this.orders = orders;
    }

    @Override
    public Optional<OrderSnapshot> handle(FindOrder query) {
        return orders.findById(new OrderId(query.orderId())).map(FindOrderHandler::toSnapshot);
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
