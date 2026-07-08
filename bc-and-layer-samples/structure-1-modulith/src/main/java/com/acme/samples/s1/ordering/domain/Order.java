package com.acme.samples.s1.ordering.domain;

import com.acme.samples.s1.shared.Money;

import java.util.List;

/** Order aggregate root. State changes only through intention-revealing methods. */
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

    /** Factory: place a new order, computing its total from the priced lines. */
    public static Order place(String id, String customerId, List<OrderLine> lines) {
        if (lines.isEmpty()) throw new IllegalArgumentException("an order needs at least one line");
        Money total = lines.stream().map(OrderLine::lineTotal).reduce(Money.usd(0), Money::plus);
        return new Order(id, customerId, lines, total, OrderStatus.PENDING);
    }

    /** Rehydrate from persistence. */
    public static Order rehydrate(String id, String customerId, List<OrderLine> lines, Money total, OrderStatus status) {
        return new Order(id, customerId, lines, total, status);
    }

    public void confirm() {
        if (status != OrderStatus.PENDING) throw new IllegalStateException("only a PENDING order can be confirmed");
        status = OrderStatus.CONFIRMED;
    }

    public void cancel() {
        if (status == OrderStatus.CONFIRMED) throw new IllegalStateException("a CONFIRMED order cannot be cancelled");
        status = OrderStatus.CANCELLED;
    }

    public String id() { return id; }
    public String customerId() { return customerId; }
    public List<OrderLine> lines() { return lines; }
    public Money total() { return total; }
    public OrderStatus status() { return status; }
}
