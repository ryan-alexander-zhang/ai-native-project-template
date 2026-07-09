package com.example.ordering.application.order;

import com.example.ordering.api.OrderPlaced;
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
import org.springframework.stereotype.Service;

/**
 * Places an order: builds the aggregate, checks the customer's credit, persists,
 * publishes the internal domain events, then announces the {@link OrderPlaced}
 * integration event to other contexts.
 */
@Service
public class PlaceOrderService {

    private final Orders orders;
    private final Customers customers;
    private final DomainEvents domainEvents;
    private final OrderPlacedPublisher orderPlacedPublisher;

    public PlaceOrderService(Orders orders, Customers customers, DomainEvents domainEvents,
                             OrderPlacedPublisher orderPlacedPublisher) {
        this.orders = orders;
        this.customers = customers;
        this.domainEvents = domainEvents;
        this.orderPlacedPublisher = orderPlacedPublisher;
    }

    public String handle(PlaceOrderCommand command) {
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

        orderPlacedPublisher.publish(toIntegrationEvent(orderId, command));
        return orderId.value();
    }

    private static OrderPlaced toIntegrationEvent(OrderId orderId, PlaceOrderCommand command) {
        List<OrderPlaced.Line> lines = command.lines().stream()
                .map(line -> new OrderPlaced.Line(line.sku(), line.quantity()))
                .toList();
        return new OrderPlaced(orderId.value(), lines);
    }
}
