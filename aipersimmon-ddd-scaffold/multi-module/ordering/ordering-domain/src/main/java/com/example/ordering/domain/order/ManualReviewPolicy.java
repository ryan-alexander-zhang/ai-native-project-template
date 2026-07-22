package com.example.ordering.domain.order;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Decides whether a freshly placed order must be held for manual review, producing the {@link
 * ReviewRequirement} that {@link Order#place} turns into an initial state. Keeping the
 * <em>decision</em> here (order classification) separate from the <em>lifecycle</em> in {@link
 * Order} is deliberate: the aggregate only knows how to move between states, not the business rule
 * for when review applies.
 *
 * <p>This reference implementation flags any order that contains a <em>restricted SKU</em> — a
 * deliberately amount-independent rule, so it never entangles with the payment authorization
 * ceiling or a customer's credit limit (both of which are value-based). A real policy would consult
 * a fraud/compliance or product-classification service; the hard-coded watchlist here is the honest
 * stand-in a scaffold needs to make the review branch reachable from a test.
 */
public final class ManualReviewPolicy {

  /** Demo watchlist: orders containing one of these SKUs are held for manual review. */
  private static final Set<String> RESTRICTED_SKUS = Set.of("SKU-RESTRICTED");

  /** Assess raw line data (before the aggregate exists), returning the review verdict. */
  public ReviewRequirement assess(List<LineData> lines) {
    Set<String> reasons = new LinkedHashSet<>();
    if (lines != null) {
      for (LineData line : lines) {
        if (RESTRICTED_SKUS.contains(line.sku())) {
          reasons.add("restricted SKU requires manual review: " + line.sku());
        }
      }
    }
    return reasons.isEmpty()
        ? ReviewRequirement.notRequired()
        : ReviewRequirement.required(reasons);
  }
}
