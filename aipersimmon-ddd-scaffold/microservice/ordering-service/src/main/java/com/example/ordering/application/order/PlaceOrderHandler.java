package com.example.ordering.application.order;

import com.aipersimmon.ddd.application.DomainEvents;
import com.aipersimmon.ddd.application.IntegrationEvents;
import com.aipersimmon.ddd.application.UseCase;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.contracts.OrderPlaced;
import com.example.ordering.domain.customer.CreditExceededException;
import com.example.ordering.domain.customer.Customer;
import com.example.ordering.domain.customer.CustomerId;
import com.example.ordering.domain.customer.Customers;
import com.example.ordering.domain.order.LineData;
import com.example.ordering.domain.order.Order;
import com.example.ordering.domain.order.OrderId;
import com.example.ordering.domain.order.Orders;
import com.example.ordering.domain.shared.Money;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Handles {@link PlaceOrder}: builds the aggregate, checks the customer's credit,
 * persists, publishes the internal domain events, then announces the
 * {@link OrderPlaced} integration event to other contexts. It is dispatched by the
 * command bus, which applies the cross-cutting concerns (logging, and — where a
 * transaction manager is present — the transaction) around it.
 */
@Component
@UseCase
public class PlaceOrderHandler implements CommandHandler<PlaceOrder, String> {

    private final Orders orders;
    private final Customers customers;
    private final DomainEvents domainEvents;
    private final IntegrationEvents integrationEvents;

    public PlaceOrderHandler(Orders orders, Customers customers, DomainEvents domainEvents,
                             IntegrationEvents integrationEvents) {
        this.orders = orders;
        this.customers = customers;
        this.domainEvents = domainEvents;
        this.integrationEvents = integrationEvents;
    }

    @Override
    public String handle(PlaceOrder command) {
        CustomerId customerId = new CustomerId(command.customerId());
        Customer customer = customers.findById(customerId)
                .orElseThrow(() -> new IllegalArgumentException("unknown customer: " + command.customerId()));

        List<LineData> lines = command.lines().stream()
                .map(line -> new LineData(line.sku(), line.quantity(),
                        Money.of(line.unitAmountMinor(), line.currency())))
                .toList();

        OrderId orderId = new OrderId(UUID.randomUUID().toString());
        Order order = Order.place(orderId, customerId, lines);

        if (!customer.canAfford(order.total())) {
            throw new CreditExceededException(
                    "customer " + customerId.value() + " cannot afford " + order.total());
        }

        orders.save(order);
        domainEvents.publishAll(order.domainEvents());
        order.clearDomainEvents();

        integrationEvents.publish(toIntegrationEvent(orderId, command));
        return orderId.value();
    }

    private static OrderPlaced toIntegrationEvent(OrderId orderId, PlaceOrder command) {
        List<OrderPlaced.Line> lines = command.lines().stream()
                .map(line -> new OrderPlaced.Line(line.sku(), line.quantity()))
                .toList();
        return new OrderPlaced(orderId.value(), lines);
    }
}
