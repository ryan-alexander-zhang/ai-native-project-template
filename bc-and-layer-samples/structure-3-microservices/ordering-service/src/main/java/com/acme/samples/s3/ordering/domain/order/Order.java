package com.acme.samples.s3.ordering.domain.order;

import com.acme.samples.s3.ordering.domain.shared.Money;

import java.util.List;

/**
 * Order aggregate root. Built and rehydrated from {@link OrderLineData} (raw
 * data), never from {@link OrderLine} instances — that keeps the internal entity
 * package-private, so callers in other packages/modules can only go through the
 * root.
 */
public class Order {

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
        return new Order(id, customerId, entities, total, OrderStatus.PENDING);
    }

    public static Order rehydrate(String id, String customerId, List<OrderLineData> lines, Money total, OrderStatus status) {
        return new Order(id, customerId, toLines(lines), total, status);
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
