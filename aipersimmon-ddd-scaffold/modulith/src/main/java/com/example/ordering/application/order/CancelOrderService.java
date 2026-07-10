package com.example.ordering.application.order;

import com.aipersimmon.ddd.application.DomainEvents;
import com.example.ordering.domain.order.Order;
import com.example.ordering.domain.order.OrderId;
import com.example.ordering.domain.order.Orders;
import org.springframework.stereotype.Service;

/** Cancels an order by driving the aggregate's state machine, then publishes its events. */
@Service
public class CancelOrderService {

    private final Orders orders;
    private final DomainEvents domainEvents;

    public CancelOrderService(Orders orders, DomainEvents domainEvents) {
        this.orders = orders;
        this.domainEvents = domainEvents;
    }

    public void cancel(String orderId) {
        OrderId id = new OrderId(orderId);
        Order order = orders.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("unknown order: " + orderId));

        order.cancel();

        orders.save(order);
        domainEvents.publishAll(order.domainEvents());
        order.clearDomainEvents();
    }
}
