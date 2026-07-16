package com.example.ordering.domain.order;

/**
 * Lifecycle states of an {@link Order}. The set is deliberately richer than a single
 * {@code PENDING}: it makes the fulfilment boundary an explicit, persistable fact
 * rather than something a policy has to infer from a saga's internal step.
 *
 * <p>{@link #FULFILMENT_IN_PROGRESS} is the pivotal state — once an order enters it the
 * customer can no longer cancel on their own, and only the fulfilment saga (holding
 * evidence of what inventory and payment did) may drive it to a terminal state.
 *
 * <p>Legal transitions are guarded inside {@link Order}: mechanical ones by a
 * {@code Transitions} table, and cancellation — which depends on <em>why</em> and on
 * evidence — by {@link OrderLifecyclePolicy}.
 */
public enum OrderStatus {

    /** Placed but held for manual review; not yet eligible for fulfilment. */
    AWAITING_REVIEW,

    /** Cleared for fulfilment (either no review needed, or review approved). */
    READY_FOR_FULFILMENT,

    /** Inventory/payment work has begun; the customer can no longer self-cancel. */
    FULFILMENT_IN_PROGRESS,

    /** Stock reserved and payment captured. */
    CONFIRMED,

    /** Dispatched to the customer; may only enter the reverse (return) flow. */
    SHIPPED,

    /** Terminated before completion, for a reason captured on the cancellation event. */
    CANCELLED
}
