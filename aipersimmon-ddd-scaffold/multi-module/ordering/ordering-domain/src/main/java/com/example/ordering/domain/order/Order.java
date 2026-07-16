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
 * The Order aggregate root. It owns its lines, guards its own lifecycle, and records domain
 * events. It refers to its customer by identity only.
 *
 * <p>Two guards protect the lifecycle, by design:
 * <ul>
 *   <li>the {@link Transitions} table below covers the <em>mechanical</em> forward moves
 *       (approve review, begin fulfilment, confirm, ship), where legality depends only on the
 *       current state; and
 *   <li>{@link OrderLifecyclePolicy} covers {@link #cancel(CancellationReason)}, whose legality
 *       depends on <em>why</em> and on evidence — something a flat table cannot express.
 * </ul>
 * Either way the aggregate remains the single place a state change happens: a policy only decides,
 * the aggregate mutates and emits the event.
 */
@AggregateRoot
public class Order extends AbstractAggregateRoot<OrderId> {

    private static final Transitions<OrderStatus> RULES = Transitions.<OrderStatus>of()
            .allow(OrderStatus.AWAITING_REVIEW, OrderStatus.READY_FOR_FULFILMENT)
            .allow(OrderStatus.READY_FOR_FULFILMENT, OrderStatus.FULFILMENT_IN_PROGRESS)
            .allow(OrderStatus.FULFILMENT_IN_PROGRESS, OrderStatus.CONFIRMED)
            .allow(OrderStatus.CONFIRMED, OrderStatus.SHIPPED);

    private static final OrderLifecyclePolicy LIFECYCLE = new OrderLifecyclePolicy();

    private static final int MAX_LINES = 100;

    private final OrderId id;
    private final CustomerId customerId;
    private final List<OrderLine> lines;
    private OrderStatus status;

    private Order(OrderId id, CustomerId customerId, List<OrderLine> lines, OrderStatus initialStatus) {
        this.id = id;
        this.customerId = customerId;
        this.lines = lines;
        this.status = initialStatus;
    }

    /**
     * Place a new order from raw line data. The manual-review verdict decides the initial state:
     * an order needing review starts {@link OrderStatus#AWAITING_REVIEW}; otherwise it is
     * {@link OrderStatus#READY_FOR_FULFILMENT}. {@code OrderPlacedEvent} means only "the order was
     * created" — it is {@link OrderReadyForFulfilmentEvent} that signals eligibility for fulfilment.
     */
    public static Order place(
            OrderId id, CustomerId customerId, List<LineData> lineData, ReviewRequirement review) {
        List<OrderLine> lines = new ArrayList<>();
        if (lineData != null) {
            for (LineData line : lineData) {
                lines.add(new OrderLine(line.sku(), line.quantity(), line.unitPrice()));
            }
        }
        if (lines.isEmpty()) {
            throw new DomainException(OrderingErrorCode.ORDER_EMPTY, "an order needs at least one line");
        }
        if (lines.size() > MAX_LINES) {
            throw new DomainException(
                    OrderingErrorCode.TOO_MANY_LINES, "an order may not exceed " + MAX_LINES + " lines");
        }
        if (review == null) {
            throw new DomainException("a review requirement must be supplied when placing an order");
        }

        OrderStatus initial =
                review.isRequired() ? OrderStatus.AWAITING_REVIEW : OrderStatus.READY_FOR_FULFILMENT;
        Order order = new Order(id, customerId, lines, initial);
        order.checkInvariant(new OrderHasDistinctSkus(lines));
        order.registerEvent(new OrderPlacedEvent(id, order.total()));
        if (initial == OrderStatus.READY_FOR_FULFILMENT) {
            order.registerEvent(new OrderReadyForFulfilmentEvent(id));
        }
        return order;
    }

    /** Manual review approved the order: it becomes eligible for fulfilment. */
    public void approveReview(ReviewDecisionRef decision) {
        if (decision == null || !decision.belongsTo(id)) {
            throw new DomainException(
                    OrderingErrorCode.REVIEW_DECISION_ORDER_MISMATCH, "the review decision does not belong to this order");
        }
        if (status != OrderStatus.AWAITING_REVIEW) {
            throw new DomainException(
                    OrderingErrorCode.ORDER_NOT_AWAITING_REVIEW, "only an order awaiting review can be approved");
        }
        RULES.check(status, OrderStatus.READY_FOR_FULFILMENT);
        this.status = OrderStatus.READY_FOR_FULFILMENT;
        registerEvent(new OrderReadyForFulfilmentEvent(id));
    }

    /** Fulfilment work begins: past this point the customer can no longer self-cancel. */
    public void beginFulfilment() {
        RULES.check(status, OrderStatus.FULFILMENT_IN_PROGRESS);
        this.status = OrderStatus.FULFILMENT_IN_PROGRESS;
        registerEvent(new OrderFulfilmentStartedEvent(id));
    }

    /** Stock reserved and payment captured. */
    public void confirm() {
        RULES.check(status, OrderStatus.CONFIRMED);
        this.status = OrderStatus.CONFIRMED;
        registerEvent(new OrderConfirmedEvent(id));
    }

    /** A confirmed order is dispatched. */
    public void ship() {
        RULES.check(status, OrderStatus.SHIPPED);
        this.status = OrderStatus.SHIPPED;
        registerEvent(new OrderShippedEvent(id));
    }

    /**
     * Cancel the order for a specific, evidence-bearing reason. The aggregate does not itself know
     * every rule — it asks {@link OrderLifecyclePolicy} to arbitrate, then (only if permitted)
     * performs the transition and emits the event. The reason type guarantees the evidence exists;
     * the policy guarantees the evidence and current state line up.
     */
    public void cancel(CancellationReason reason) {
        if (reason == null) {
            throw new DomainException("a cancellation must state its reason");
        }
        LIFECYCLE.ensureCancellable(id, customerId, status, reason);
        this.status = OrderStatus.CANCELLED;
        registerEvent(new OrderCancelledEvent(id, CancellationCategory.from(reason)));
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
