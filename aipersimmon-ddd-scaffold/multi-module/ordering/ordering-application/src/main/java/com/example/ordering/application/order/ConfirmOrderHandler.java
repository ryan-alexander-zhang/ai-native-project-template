package com.example.ordering.application.order;

import com.aipersimmon.ddd.application.DomainEvents;
import com.aipersimmon.ddd.application.UseCase;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.ordering.domain.order.Order;
import com.example.ordering.domain.order.OrderId;
import com.example.ordering.domain.order.Orders;
import java.util.NoSuchElementException;
import org.springframework.stereotype.Component;

/** Handles {@link ConfirmOrder}: drives the aggregate's state machine, then publishes its events. */
@Component
@UseCase
public class ConfirmOrderHandler implements CommandHandler<ConfirmOrder, Void> {

    private final Orders orders;
    private final DomainEvents domainEvents;

    public ConfirmOrderHandler(Orders orders, DomainEvents domainEvents) {
        this.orders = orders;
        this.domainEvents = domainEvents;
    }

    @Override
    public Void handle(ConfirmOrder command) {
        OrderId id = new OrderId(command.orderId());
        Order order = orders.findById(id)
                .orElseThrow(() -> new NoSuchElementException("unknown order: " + command.orderId()));

        order.confirm();

        orders.save(order);
        domainEvents.publishAll(order.domainEvents());
        order.clearDomainEvents();
        return null;
    }
}
