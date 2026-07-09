package com.acme.samples.s3.ordering.app.order;

import com.acme.samples.s3.ordering.client.OrderPlaced;
import com.acme.samples.s3.ordering.domain.customer.Customer;
import com.acme.samples.s3.ordering.domain.customer.Customers;
import com.acme.samples.s3.ordering.domain.order.Order;
import com.acme.samples.s3.ordering.domain.order.OrderLineData;
import com.acme.samples.s3.ordering.domain.order.Orders;
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
    private final InventoryPort inventory;
    private final OrderPlacedPublisher publisher;

    public PlaceOrderService(Customers customers, Orders orders, PricingPort pricing,
                             InventoryPort inventory, OrderPlacedPublisher publisher) {
        this.customers = customers;
        this.orders = orders;
        this.pricing = pricing;
        this.inventory = inventory;
        this.publisher = publisher;
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

        // synchronous cross-service REST pre-check against inventory-service
        for (OrderLineData line : lines) {
            if (!inventory.isAvailable(line.sku(), line.qty())) {
                throw new StockUnavailableException("sku " + line.sku() + " not available x" + line.qty());
            }
        }

        orders.save(order);
        publisher.publish(new OrderPlaced(order.id(), order.customerId(),
                order.lines().stream()
                        .map(l -> new OrderPlaced.Line(l.sku(), l.qty(), l.unitPriceMinor()))
                        .toList()));
        return order.id();
    }
}
