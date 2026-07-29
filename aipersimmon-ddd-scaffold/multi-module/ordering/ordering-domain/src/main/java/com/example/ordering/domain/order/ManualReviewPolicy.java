package com.example.ordering.domain.order;

import java.util.List;

/**
 * Decides whether a freshly placed order must be held for manual review, producing the {@link
 * ReviewRequirement} that {@link Order#place} turns into an initial state. Keeping the
 * <em>decision</em> here (order classification) separate from the <em>lifecycle</em> in {@link
 * Order} is deliberate: the aggregate only knows how to move between states, not the business rule
 * for when review applies.
 *
 * <p><strong>A port, not a class, because this is the rule most likely to be replaced.</strong> It
 * used to be a final class holding a hard-coded SKU watchlist, instantiated with {@code new} in a
 * {@code private static final} field of {@code PlaceOrderHandler}. That made the single most
 * business-variable rule in the context the one thing a consuming project could not change without
 * editing a handler — the opposite of what a scaffold should offer. {@link
 * RestrictedSkuReviewPolicy} is still here and still simple; it is now <em>one</em> implementation
 * of a port rather than the only possible answer.
 *
 * <p>What a real deployment substitutes: a fraud or compliance service, a product-classification
 * lookup, a rules engine, or a value-based rule. Declare a bean of this type and the scaffold's
 * default backs off — see {@code OrderingPolicyConfig} in {@code start}.
 *
 * <p>Implementations must stay <em>pure</em>: given the same lines, the same verdict. This is
 * consulted inside the placement transaction, before the aggregate exists, so an implementation
 * that reaches out to a slow or flaky service is putting that call on the write path. If review
 * classification has to be remote, prefer holding every order and clearing it asynchronously over
 * making placement depend on a network hop.
 */
public interface ManualReviewPolicy {

  /**
   * Assess raw line data (before the aggregate exists), returning the review verdict.
   *
   * @param lines the order's lines; may be {@code null} or empty, and that must not throw — {@link
   *     Order#place} rejects an empty order itself, and a policy that threw first would surface the
   *     wrong error
   */
  ReviewRequirement assess(List<LineData> lines);
}
