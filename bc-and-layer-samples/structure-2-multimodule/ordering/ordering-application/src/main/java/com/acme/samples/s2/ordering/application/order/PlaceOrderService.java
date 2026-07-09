package com.acme.samples.s2.ordering.application.order;

import com.acme.samples.s2.ordering.domain.customer.Customer;
import com.acme.samples.s2.ordering.domain.customer.Customers;
import com.acme.samples.s2.ordering.domain.order.Order;
import com.acme.samples.s2.ordering.domain.order.OrderLineData;
import com.acme.samples.s2.ordering.domain.order.Orders;
import com.acme.samples.s2.shared.AggregateChanges;
import com.acme.samples.s2.shared.CommandHandler;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Command handler for {@link PlaceOrderCommand}. Thin: load aggregates, invoke
 * domain behaviour, persist, register the aggregate for domain-event drain. No
 * {@code @Transactional} here — the CommandBus's Transaction decorator owns the
 * UnitOfWork (transaction + domain-event dispatch), analysis-00005 §5.1.
 */
@Service
public class PlaceOrderService implements CommandHandler<PlaceOrderCommand, String> {

    private final Customers customers;
    private final Orders orders;
    private final PricingPort pricing;
    private final AggregateChanges changes;

    public PlaceOrderService(Customers customers, Orders orders, PricingPort pricing, AggregateChanges changes) {
        this.customers = customers;
        this.orders = orders;
        this.pricing = pricing;
        this.changes = changes;
    }

    @Override
    public String handle(PlaceOrderCommand command) {
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
        changes.register(order);   // UnitOfWork decorator drains + publishes OrderPlacedEvent in-tx
        return order.id();
    }
}
