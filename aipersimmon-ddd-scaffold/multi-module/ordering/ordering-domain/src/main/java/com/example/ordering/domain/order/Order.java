package com.example.ordering.domain.order;

import com.aipersimmon.ddd.core.annotation.AggregateRoot;
import com.aipersimmon.ddd.core.annotation.Identity;
import com.aipersimmon.ddd.core.exception.DomainException;
import com.aipersimmon.ddd.core.model.AbstractAggregateRoot;
import com.aipersimmon.ddd.core.state.Transitions;
import com.example.ordering.domain.customer.CustomerId;
import com.example.ordering.domain.shared.Money;
import com.example.ordering.domain.shared.OrderingErrorCode;
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

    private static final int MAX_LINES = 100;

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
        List<OrderLine> lines = new ArrayList<>();
        if (lineData != null) {
            for (LineData line : lineData) {
                lines.add(new OrderLine(line.sku(), line.quantity(), line.unitPrice()));
            }
        }
        // Trivial guards stay as coded throws; only a non-trivial, named invariant
        // (no repeated SKU across lines) is worth expressing as an Invariant.
        if (lines.isEmpty()) {
            throw new DomainException(OrderingErrorCode.ORDER_EMPTY, "an order needs at least one line");
        }
        if (lines.size() > MAX_LINES) {
            throw new DomainException(
                    OrderingErrorCode.TOO_MANY_LINES, "an order may not exceed " + MAX_LINES + " lines");
        }
        Order order = new Order(id, customerId, lines);
        order.checkInvariant(new OrderHasDistinctSkus(lines));
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
