package com.example.samples.s26.catalog.application;

import com.example.samples.s26.catalog.domain.Sku;
import java.time.Instant;

/**
 * The append-only sales facts, and the source of truth for how much of a product has sold.
 *
 * <p><strong>Not an aggregate, and not modelled as one.</strong> Ordering is another context's subject
 * (S12 and S23 model it); here a sold line is a fact that arrives, with no invariant of its own to
 * protect and no behaviour to invoke. Dressing it as an aggregate would add a root, a version column and
 * an optimistic-lock check to something nothing ever updates. What matters for this sample is only that
 * it is the <em>source</em>: both the expensive read and the projection derive from it, which is what
 * makes them comparable.
 */
public interface OrderLines {

  /** Record that {@code quantity} of {@code sku} sold at {@code placedAt}. */
  void append(String id, Sku sku, int quantity, Instant placedAt);
}
