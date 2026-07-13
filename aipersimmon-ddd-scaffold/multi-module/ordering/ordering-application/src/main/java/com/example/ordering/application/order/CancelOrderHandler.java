package com.example.ordering.application.order;

import com.aipersimmon.ddd.application.DomainEvents;
import com.aipersimmon.ddd.application.UseCase;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.ordering.domain.order.Order;
import com.example.ordering.domain.order.OrderId;
import com.example.ordering.domain.order.Orders;
import java.util.NoSuchElementException;
import org.springframework.stereotype.Component;

/** Handles {@link CancelOrder}: drives the aggregate's state machine, then publishes its events. */
@Component
@UseCase
public class CancelOrderHandler implements CommandHandler<CancelOrder, Void> {

    private final Orders orders;
    private final DomainEvents domainEvents;

    public CancelOrderHandler(Orders orders, DomainEvents domainEvents) {
        this.orders = orders;
        this.domainEvents = domainEvents;
    }

    @Override
    public Void handle(CancelOrder command) {
        OrderId id = new OrderId(command.orderId());
        Order order = orders.findById(id)
                .orElseThrow(() -> new NoSuchElementException("unknown order: " + command.orderId()));

        order.cancel();

        orders.save(order);
        domainEvents.publishAll(order.domainEvents());
        order.clearDomainEvents();
        return null;
    }
}
