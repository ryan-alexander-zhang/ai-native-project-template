package com.acme.samples.s2.ordering.application.order;

import com.acme.samples.s2.ordering.domain.customer.Customer;
import com.acme.samples.s2.ordering.domain.customer.Customers;
import com.acme.samples.s2.ordering.domain.order.Order;
import com.acme.samples.s2.ordering.domain.order.OrderLineData;
import com.acme.samples.s2.ordering.domain.order.Orders;
import com.acme.samples.s2.shared.DomainEvents;
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
    private final DomainEvents domainEvents;

    public PlaceOrderService(Customers customers, Orders orders, PricingPort pricing, DomainEvents domainEvents) {
        this.customers = customers;
        this.orders = orders;
        this.pricing = pricing;
        this.domainEvents = domainEvents;
    }

    @Transactional
    public String place(PlaceOrder command) {
        Customer customer = customers.byId(command.customerId())
                .orElseThrow(() -> new IllegalArgumentException("unknown customer: " + command.customerId()));

        // build raw line data (external HTTP pricing per sku); the aggregate builds its own internal lines
        List<OrderLineData> lines = command.lines().stream()
                .map(l -> new OrderLineData(l.sku(), l.qty(), pricing.unitPriceMinor(l.sku())))
                .toList();

        Order order = Order.place(UUID.randomUUID().toString(), command.customerId(), lines);

        if (!customer.canAfford(order.total())) {
            throw new CreditExceededException("order total " + order.total().amountMinor()
                    + " exceeds credit for customer " + command.customerId());
        }

        orders.save(order);
        // Publish the recorded domain event(s) in-process, same transaction. An
        // @EventListener translates OrderPlacedEvent -> the OrderPlaced integration
        // event (written to the outbox) and updates the read model (OrderEventsHandler).
        domainEvents.publish(order.domainEvents());
        return order.id();
    }
}
