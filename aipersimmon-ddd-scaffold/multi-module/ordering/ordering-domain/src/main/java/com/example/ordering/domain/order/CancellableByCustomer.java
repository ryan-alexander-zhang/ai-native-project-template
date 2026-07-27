package com.example.ordering.domain.order;

import com.aipersimmon.ddd.core.rule.Specification;
import com.example.ordering.domain.customer.CustomerId;

/**
 * Whether this customer may still cancel this order themselves — a question, not an assertion.
 *
 * <p>The rule already existed, expressed the only way the model could express it: {@link
 * OrderLifecyclePolicy} throws when a customer cancellation is not allowed. That is the right shape
 * for a <em>write</em>, where the answer is "refuse", but it is the wrong shape for everyone who
 * merely wants to know — a client deciding whether to offer a Cancel button had to attempt the
 * cancellation and read the error to find out. That is an exception used as control flow, and it is
 * precisely the split {@code Specification} and {@code Invariant} exist to keep apart.
 *
 * <p>So the rule lives here, and the policy asks it. One statement of "before fulfilment starts,
 * and only your own order", consulted by both the question and the refusal — rather than two copies
 * that agree until someone edits one of them.
 *
 * <p>Two rules apply, and they are kept apart because they fail for different reasons: <em>who</em>
 * is asking, and <em>when</em>. The "when" half is the substantive one and is published as {@link
 * #BEFORE_FULFILMENT} so the policy uses the same statement of it; the "who" half is an identity
 * comparison, not worth a name of its own.
 */
public final class CancellableByCustomer implements Specification<Order> {

  /**
   * The customer's window closes the moment fulfilment starts. Judged on the status rather than on
   * the order, so the rule has exactly one home: {@link OrderLifecyclePolicy}, which sees only the
   * facts and not the aggregate, asks this same instance before refusing.
   */
  public static final Specification<OrderStatus> BEFORE_FULFILMENT =
      status -> status == OrderStatus.AWAITING_REVIEW || status == OrderStatus.READY_FOR_FULFILMENT;

  private final CustomerId requestedBy;

  public CancellableByCustomer(CustomerId requestedBy) {
    this.requestedBy = requestedBy;
  }

  @Override
  public boolean isSatisfiedBy(Order order) {
    return order != null
        && order.customerId().equals(requestedBy)
        && BEFORE_FULFILMENT.isSatisfiedBy(order.status());
  }
}
