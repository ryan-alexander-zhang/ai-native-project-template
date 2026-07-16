package com.example.ordering.domain.order;

/**
 * A reference to a fact that happened <em>outside</em> the Order aggregate — a stock
 * release, a payment decline, a review decision — reduced to what Ordering is allowed
 * to know: a stable id and the order it pertains to. Cross-context detail is translated
 * into these refs at the anti-corruption boundary (the adapter / process manager); the
 * domain never sees a foreign contract type.
 *
 * <p>Their whole purpose is to make evidence <em>checkable</em>: a caller cannot claim
 * "stock was released for this order" without producing a {@link StockReleaseRef} that
 * {@link #belongsTo(OrderId) belongs to} that order. That is what lets
 * {@link OrderLifecyclePolicy} reject a compensation whose evidence does not line up.
 */
public interface OrderEvidenceRef {

    /** The order this piece of evidence was produced for. */
    OrderId orderId();

    /** Whether this evidence pertains to the given order. */
    default boolean belongsTo(OrderId orderId) {
        return orderId().equals(orderId);
    }
}
