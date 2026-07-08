package com.acme.samples.s2.ordering.application;

import com.acme.samples.s2.ordering.api.OrderPlaced;
import com.acme.samples.s2.ordering.domain.Customer;
import com.acme.samples.s2.ordering.domain.Customers;
import com.acme.samples.s2.ordering.domain.Order;
import com.acme.samples.s2.ordering.domain.OrderLine;
import com.acme.samples.s2.ordering.domain.Orders;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class PlaceOrderService {

    public record PlaceOrder(String customerId, List<Line> lines) {
        public record Line(String sku, int qty) {}
    }

    private final Customers customers;
    private final Orders orders;
    private final PricingPort pricing;
    private final OrderPlacedPublisher publisher;

    public PlaceOrderService(Customers customers, Orders orders, PricingPort pricing, OrderPlacedPublisher publisher) {
        this.customers = customers;
        this.orders = orders;
        this.pricing = pricing;
        this.publisher = publisher;
    }

    @Transactional
    public String place(PlaceOrder command) {
        Customer customer = customers.byId(command.customerId())
                .orElseThrow(() -> new IllegalArgumentException("unknown customer: " + command.customerId()));

        List<OrderLine> lines = command.lines().stream()
                .map(l -> new OrderLine(l.sku(), l.qty(), pricing.unitPriceMinor(l.sku())))
                .toList();

        Order order = Order.place(UUID.randomUUID().toString(), command.customerId(), lines);

        if (!customer.canAfford(order.total())) {
            throw new CreditExceededException("order total " + order.total().amountMinor()
                    + " exceeds credit for customer " + command.customerId());
        }

        orders.save(order);

        // outbox write happens in this same transaction
        publisher.publish(new OrderPlaced(order.id(), order.customerId(),
                order.lines().stream()
                        .map(l -> new OrderPlaced.Line(l.sku(), l.qty(), l.unitPriceMinor()))
                        .toList()));

        return order.id();
    }
}
