package com.acme.samples.s2.ordering.domain.order;

import com.acme.samples.s2.shared.AggregateRoot;
import com.acme.samples.s2.shared.Money;

import java.util.List;

/**
 * Order aggregate root. Built and rehydrated from {@link OrderLineData} (raw
 * data), never from {@link OrderLine} instances — that keeps the internal entity
 * package-private, so callers in other packages/modules can only go through the
 * root. Extends {@link AggregateRoot} to record domain events on state changes.
 */
public class Order extends AggregateRoot {

    private final String id;
    private final String customerId;
    private final List<OrderLine> lines;
    private final Money total;
    private OrderStatus status;

    private Order(String id, String customerId, List<OrderLine> lines, Money total, OrderStatus status) {
        this.id = id;
        this.customerId = customerId;
        this.lines = List.copyOf(lines);
        this.total = total;
        this.status = status;
    }

    private static List<OrderLine> toLines(List<OrderLineData> data) {
        return data.stream().map(d -> new OrderLine(d.sku(), d.qty(), d.unitPriceMinor())).toList();
    }

    public static Order place(String id, String customerId, List<OrderLineData> lines) {
        if (lines.isEmpty()) throw new IllegalArgumentException("an order needs at least one line");
        List<OrderLine> entities = toLines(lines);
        Money total = entities.stream().map(OrderLine::lineTotal).reduce(Money.usd(0), Money::plus);
        Order order = new Order(id, customerId, entities, total, OrderStatus.PENDING);
        order.registerEvent(new OrderPlacedEvent(id, customerId, List.copyOf(lines), total));
        return order;
    }

    public static Order rehydrate(String id, String customerId, List<OrderLineData> lines, Money total, OrderStatus status) {
        return new Order(id, customerId, toLines(lines), total, status);
    }

    public void confirm() {
        if (status != OrderStatus.PENDING) throw new IllegalStateException("only a PENDING order can be confirmed");
        status = OrderStatus.CONFIRMED;
        registerEvent(new OrderConfirmedEvent(id));
    }

    public void cancel() {
        if (status == OrderStatus.CONFIRMED) throw new IllegalStateException("a CONFIRMED order cannot be cancelled");
        status = OrderStatus.CANCELLED;
        registerEvent(new OrderCancelledEvent(id, "stock not reserved"));
    }

    public String id() { return id; }
    public String customerId() { return customerId; }
    public Money total() { return total; }
    public OrderStatus status() { return status; }

    /** Public view of the lines for persistence; internal {@link OrderLine} stays hidden. */
    public List<OrderLineData> lines() {
        return lines.stream().map(l -> new OrderLineData(l.sku(), l.qty(), l.unitPriceMinor())).toList();
    }
}
