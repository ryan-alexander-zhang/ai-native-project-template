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
 * The Order aggregate root. It owns its lines, guards its own lifecycle, and records domain events.
 * It refers to its customer by identity only.
 *
 * <p>Two guards protect the lifecycle, by design:
 *
 * <ul>
 *   <li>the {@link Transitions} table below covers the <em>mechanical</em> forward moves (approve
 *       review, begin fulfilment, confirm, ship), where legality depends only on the current state;
 *       and
 *   <li>{@link OrderLifecyclePolicy} covers {@link #cancel(CancellationReason)}, whose legality
 *       depends on <em>why</em> and on evidence — something a flat table cannot express.
 * </ul>
 *
 * Either way the aggregate remains the single place a state change happens: a policy only decides,
 * the aggregate mutates and emits the event.
 */
@AggregateRoot
public class Order extends AbstractAggregateRoot<OrderId> {

  // Each edge also names its refusal: the whole mechanical lifecycle contract —
  // what is legal, and what a refusal is called at the edge — reads off this one table.
  private static final Transitions<OrderStatus> RULES =
      Transitions.<OrderStatus>of()
          .allow(
              OrderStatus.AWAITING_REVIEW,
              OrderStatus.READY_FOR_FULFILMENT,
              OrderingErrorCode.ORDER_NOT_AWAITING_REVIEW)
          .allow(
              OrderStatus.READY_FOR_FULFILMENT,
              OrderStatus.FULFILMENT_IN_PROGRESS,
              OrderingErrorCode.ORDER_NOT_READY_FOR_FULFILMENT)
          .allow(
              OrderStatus.FULFILMENT_IN_PROGRESS,
              OrderStatus.CONFIRMED,
              OrderingErrorCode.ORDER_NOT_UNDER_FULFILMENT)
          .allow(OrderStatus.CONFIRMED, OrderStatus.SHIPPED, OrderingErrorCode.ORDER_NOT_CONFIRMED);

  private static final OrderLifecyclePolicy LIFECYCLE = new OrderLifecyclePolicy();

  private static final int MAX_LINES = 100;

  private final OrderId id;
  private final CustomerId customerId;
  private final List<OrderLine> lines;
  private OrderStatus status;

  /**
   * Whether this instance's line set differs from what is stored.
   *
   * <p>Transient — never persisted, never part of the aggregate's identity or equality. It exists
   * so a persistence adapter can ask the aggregate a question only the aggregate can answer ("have
   * my lines changed?") instead of guessing, which it previously did by rewriting the whole line
   * set on every save: a confirm or a cancel touches only {@code status}, yet each one deleted and
   * re-inserted every line at its own version.
   *
   * <p>Today it is only ever true for a freshly {@link #place}d order, because nothing else mutates
   * the line set. That is the point rather than a shortcut: if a line-editing use case is added
   * later, it sets this flag and the persistence adapter needs no change.
   */
  private boolean lineSetChanged;

  private Order(
      OrderId id, CustomerId customerId, List<OrderLine> lines, OrderStatus initialStatus) {
    this.id = id;
    this.customerId = customerId;
    this.lines = lines;
    this.status = initialStatus;
  }

  /**
   * Place a new order from raw line data. The manual-review verdict decides the initial state: an
   * order needing review starts {@link OrderStatus#AWAITING_REVIEW}; otherwise it is {@link
   * OrderStatus#READY_FOR_FULFILMENT}. {@code OrderPlacedEvent} means only "the order was created"
   * — it is {@link OrderReadyForFulfilmentEvent} that signals eligibility for fulfilment.
   *
   * <p><strong>This takes the review verdict, not the review policy — a stated trade-off.</strong>
   * Passing {@link ManualReviewPolicy} instead and letting the aggregate ask it would make "every
   * placement is reviewed" unforgeable here; taking the verdict leaves that force in the
   * application layer's hands, and a caller passing {@code ReviewRequirement.notRequired()}
   * bypasses review. Chosen anyway, for two reasons: the double dispatch does not actually close
   * the hole (a caller who would forge a verdict can as easily pass an always-approving policy),
   * and the verdict-as-value keeps this factory deterministic and policy-free — the policy is
   * consulted once, in {@code PlaceOrderHandler}, where its configuration lives, and tests place
   * orders in any review state without staging policy internals.
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
    order.lineSetChanged = true;
    order.checkInvariant(new OrderHasDistinctSkus(lines));
    order.checkInvariant(new OrderHasSingleCurrency(lines));
    order.registerEvent(new OrderPlacedEvent(id, order.total()));
    if (initial == OrderStatus.READY_FOR_FULFILMENT) {
      order.registerEvent(new OrderReadyForFulfilmentEvent(id));
    }
    return order;
  }

  /**
   * Reconstitute a stored order: sets state directly and registers no events. For persistence
   * adapters only — application code creates orders through {@link #place}. Framework-free (no
   * persistence annotations here; the ORM/mapper lives in the infrastructure layer).
   *
   * @param version the row's optimistic-lock version, which the repository puts back in the {@code
   *     WHERE} clause when it saves; passing it through the aggregate's own factory is what keeps a
   *     repository from setting the version behind the aggregate's back
   */
  public static Order reconstitute(
      OrderId id,
      CustomerId customerId,
      List<LineData> lineData,
      OrderStatus status,
      long version) {
    List<OrderLine> lines = new ArrayList<>();
    if (lineData != null) {
      for (LineData line : lineData) {
        lines.add(new OrderLine(line.sku(), line.quantity(), line.unitPrice()));
      }
    }
    Order order = new Order(id, customerId, lines, status);
    order.restoreVersion(version);
    return order;
  }

  /**
   * Whether the stored line set needs rewriting — see {@link #lineSetChanged}. Called by the
   * persistence adapter; {@code false} on a reconstituted order whose lines were never touched.
   */
  public boolean lineSetChanged() {
    return lineSetChanged;
  }

  /** This order's lines as raw {@link LineData}, so a persistence adapter can store them. */
  public List<LineData> lineData() {
    List<LineData> out = new ArrayList<>();
    for (OrderLine line : lines) {
      out.add(new LineData(line.sku(), line.quantity(), line.unitPrice()));
    }
    return out;
  }

  /**
   * Manual review approved the order: it becomes eligible for fulfilment. The state guard is the
   * transition table's — it refuses a non-awaiting order with {@code ORDER_NOT_AWAITING_REVIEW},
   * declared on the edge itself, so this method no longer restates the same rule by hand just to
   * attach the code. Taking {@link ReviewDecisionRef.Approval} rather than the interface is the
   * point of the sealed split: a rejection cannot be handed to the approving method at all.
   */
  public void approveReview(ReviewDecisionRef.Approval decision) {
    if (decision == null || !decision.belongsTo(id)) {
      throw new DomainException(
          OrderingErrorCode.REVIEW_DECISION_ORDER_MISMATCH,
          "the review decision does not belong to this order");
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

  /** Stock reserved and payment authorized. */
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
