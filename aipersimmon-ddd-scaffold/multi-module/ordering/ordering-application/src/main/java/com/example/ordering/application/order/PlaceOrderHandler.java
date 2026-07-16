package com.example.ordering.application.order;

import com.aipersimmon.ddd.application.DomainEvents;
import com.aipersimmon.ddd.application.EntityNotFoundException;
import com.aipersimmon.ddd.application.IntegrationEvents;
import com.aipersimmon.ddd.application.UseCase;
import com.aipersimmon.ddd.core.exception.DomainException;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandHandler;
import com.example.ordering.api.OrderPlaced;
import com.example.ordering.application.order.StockAvailabilityGateway.Availability;
import com.example.ordering.domain.customer.CreditExceededException;
import com.example.ordering.domain.customer.Customer;
import com.example.ordering.domain.customer.CustomerId;
import com.example.ordering.domain.customer.Customers;
import com.example.ordering.domain.order.LineData;
import com.example.ordering.domain.order.Order;
import com.example.ordering.domain.order.OrderId;
import com.example.ordering.domain.order.Orders;
import com.example.ordering.domain.order.ReviewRequirement;
import com.example.ordering.domain.shared.Money;
import com.example.ordering.domain.shared.OrderingErrorCode;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Handles {@link PlaceOrder}: builds the aggregate, checks the customer's credit,
 * persists, publishes the internal domain events, then announces the
 * {@link OrderPlaced} integration event to other contexts. It is dispatched by the
 * command bus, which applies the cross-cutting concerns (logging, and — where a
 * transaction manager is present — the transaction) around it.
 *
 * <p>Before creating anything it calls the {@link StockAvailabilityGateway} — a
 * synchronous, cross-context query into the inventory context — to fail fast on an
 * order whose SKUs inventory cannot currently offer. This is deliberately a
 * <em>read</em>: the authoritative stock <em>reservation</em> is a state change and
 * stays on the asynchronous {@link OrderPlaced} -&gt; reserve-stock -&gt; saga path.
 * The two are complementary — the query gives an immediate, user-facing rejection for
 * hopeless orders; the event/saga does the atomic, compensable reservation for
 * everything that passes the gate.
 */
@Component
@UseCase
public class PlaceOrderHandler implements CommandHandler<PlaceOrder, String> {

    private final Orders orders;
    private final Customers customers;
    private final DomainEvents domainEvents;
    private final IntegrationEvents integrationEvents;
    private final StockAvailabilityGateway stockAvailability;

    public PlaceOrderHandler(Orders orders, Customers customers, DomainEvents domainEvents,
                             IntegrationEvents integrationEvents,
                             StockAvailabilityGateway stockAvailability) {
        this.orders = orders;
        this.customers = customers;
        this.domainEvents = domainEvents;
        this.integrationEvents = integrationEvents;
        this.stockAvailability = stockAvailability;
    }

    @Override
    public String handle(PlaceOrder command, CommandContext context) {
        CustomerId customerId = new CustomerId(command.customerId());
        Customer customer = customers.findById(customerId)
                .orElseThrow(() -> new EntityNotFoundException(
                        OrderingErrorCode.CUSTOMER_NOT_FOUND, "unknown customer: " + command.customerId()));

        // Fail fast: synchronously ask the inventory context (through the anti-corruption
        // gateway) whether it can offer these SKUs at all, before creating the order. The
        // authoritative quantity reservation still happens asynchronously via OrderPlaced.
        List<String> skus = command.lines().stream().map(PlaceOrder.Line::sku).distinct().toList();
        Availability availability = stockAvailability.check(skus);
        if (!availability.allAvailable()) {
            throw new DomainException(OrderingErrorCode.STOCK_UNAVAILABLE,
                    "inventory cannot currently offer: " + availability.unavailableSkus());
        }

        List<LineData> lines = command.lines().stream()
                .map(line -> new LineData(line.sku(), line.quantity(),
                        Money.of(line.unitAmountMinor(), line.currency())))
                .toList();

        OrderId orderId = new OrderId(UUID.randomUUID().toString());
        // This scaffold does not model manual review, so orders are placed review-free and are
        // immediately eligible for fulfilment. A real ManualReviewPolicy would compute this verdict.
        Order order = Order.place(orderId, customerId, lines, ReviewRequirement.notRequired());

        if (!customer.canAfford(order.total())) {
            throw new CreditExceededException(
                    "customer " + customerId.value() + " cannot afford " + order.total());
        }

        // Placing the order publishes the OrderPlaced integration event below, which asks inventory
        // to reserve stock — so fulfilment has genuinely begun. Record that fact now (moving past the
        // customer's self-cancel window) before the async StockReserved/Failed response can arrive.
        order.beginFulfilment();

        orders.save(order);
        domainEvents.publishAndClear(order);

        integrationEvents.publish(toIntegrationEvent(orderId, command), context);
        return orderId.value();
    }

    private static OrderPlaced toIntegrationEvent(OrderId orderId, PlaceOrder command) {
        List<OrderPlaced.Line> lines = command.lines().stream()
                .map(line -> new OrderPlaced.Line(line.sku(), line.quantity()))
                .toList();
        return new OrderPlaced(orderId.value(), lines);
    }
}
