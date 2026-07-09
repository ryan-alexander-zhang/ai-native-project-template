package com.example.ordering.domain.order;

import com.aipersimmon.ddd.core.annotation.AggregateRoot;
import com.aipersimmon.ddd.core.annotation.Identity;
import com.aipersimmon.ddd.core.exception.DomainException;
import com.aipersimmon.ddd.core.model.AbstractAggregateRoot;
import com.aipersimmon.ddd.core.state.Transitions;
import com.example.ordering.domain.customer.CustomerId;
import com.example.ordering.domain.shared.Money;
import java.util.ArrayList;
import java.util.List;

/**
 * The Order aggregate root. It owns its lines, guards its own lifecycle, and
 * records domain events. It refers to its customer by identity only.
 */
@AggregateRoot
public class Order extends AbstractAggregateRoot<OrderId> {

    private static final Transitions<OrderStatus> RULES = Transitions.<OrderStatus>of()
            .allow(OrderStatus.PENDING, OrderStatus.CONFIRMED)
            .allow(OrderStatus.PENDING, OrderStatus.CANCELLED);

    private final OrderId id;
    private final CustomerId customerId;
    private final List<OrderLine> lines;
    private OrderStatus status;

    private Order(OrderId id, CustomerId customerId, List<OrderLine> lines) {
        this.id = id;
        this.customerId = customerId;
        this.lines = lines;
        this.status = OrderStatus.PENDING;
    }

    /** Place a new order from raw line data, recording the order-placed event. */
    public static Order place(OrderId id, CustomerId customerId, List<LineData> lineData) {
        if (lineData == null || lineData.isEmpty()) {
            throw new DomainException("an order needs at least one line");
        }
        List<OrderLine> lines = new ArrayList<>();
        for (LineData line : lineData) {
            lines.add(new OrderLine(line.sku(), line.quantity(), line.unitPrice()));
        }
        Order order = new Order(id, customerId, lines);
        order.registerEvent(new OrderPlacedEvent(id, order.total()));
        return order;
    }

    public void confirm() {
        RULES.check(status, OrderStatus.CONFIRMED);
        this.status = OrderStatus.CONFIRMED;
        registerEvent(new OrderConfirmedEvent(id));
    }

    public void cancel() {
        RULES.check(status, OrderStatus.CANCELLED);
        this.status = OrderStatus.CANCELLED;
    }

    public Money total() {
        return lines.stream()
                .map(OrderLine::subtotal)
                .reduce(Money::plus)
                .orElseThrow(() -> new DomainException("order has no lines"));
    }

    @Override
    @Identity
    public OrderId id() {
        return id;
    }

    public CustomerId customerId() {
        return customerId;
    }

    public OrderStatus status() {
        return status;
    }
}
