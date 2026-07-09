package com.acme.samples.s1.ordering.application.order;

import com.acme.samples.s1.ordering.domain.customer.Customer;
import com.acme.samples.s1.ordering.domain.customer.Customers;
import com.acme.samples.s1.ordering.domain.order.Order;
import com.acme.samples.s1.ordering.domain.order.OrderLineData;
import com.acme.samples.s1.ordering.domain.order.Orders;
import com.acme.samples.s1.shared.OrderPlaced;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Use case: place an order. Thin — load aggregates, invoke domain behaviour,
 * persist, publish the integration event. All in one transaction; the published
 * event is captured by the Spring Modulith event registry (outbox) and relayed
 * to Kafka after commit.
 */
@Service
public class PlaceOrderService {

    /** Inbound command. */
    public record PlaceOrder(String customerId, List<Line> lines) {
        public record Line(String sku, int qty) {}
    }

    private final Customers customers;
    private final Orders orders;
    private final PricingPort pricing;
    private final ApplicationEventPublisher events;

    public PlaceOrderService(Customers customers, Orders orders, PricingPort pricing, ApplicationEventPublisher events) {
        this.customers = customers;
        this.orders = orders;
        this.pricing = pricing;
        this.events = events;
    }

    @Transactional
    public String place(PlaceOrder command) {
        // cross-aggregate: the Order references the Customer aggregate by id
        Customer customer = customers.byId(command.customerId())
                .orElseThrow(() -> new IllegalArgumentException("unknown customer: " + command.customerId()));

        // external HTTP: price each line via the pricing service; the aggregate builds its own internal lines
        List<OrderLineData> lines = command.lines().stream()
                .map(l -> new OrderLineData(l.sku(), l.qty(), pricing.unitPriceMinor(l.sku())))
                .toList();

        Order order = Order.place(UUID.randomUUID().toString(), command.customerId(), lines);

        // aggregate invariant checked against the other aggregate
        if (!customer.canAfford(order.total())) {
            throw new CreditExceededException("order total " + order.total().amountMinor()
                    + " exceeds credit for customer " + command.customerId());
        }

        orders.save(order);

        events.publishEvent(new OrderPlaced(
                order.id(),
                order.customerId(),
                order.lines().stream()
                        .map(l -> new OrderPlaced.Line(l.sku(), l.qty(), l.unitPriceMinor()))
                        .toList()));

        return order.id();
    }
}
