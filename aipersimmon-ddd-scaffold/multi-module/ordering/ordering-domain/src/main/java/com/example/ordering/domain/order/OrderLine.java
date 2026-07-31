package com.example.ordering.domain.order;

import com.aipersimmon.ddd.core.annotation.ValueObject;
import com.aipersimmon.ddd.core.exception.DomainException;
import com.example.ordering.domain.shared.Money;
import com.example.ordering.domain.shared.OrderingErrorCode;
import com.example.ordering.domain.shared.Sku;

/**
 * A line of an {@link Order}. Package-private on purpose: it is internal to the aggregate, so
 * nothing outside this package can construct or reference it — the only way in is through {@link
 * Order}.
 *
 * <p>A value object, not an entity (issue-00150): it carries no identity of its own, no lifecycle,
 * every component is final, and persistence rewrites the whole line set rather than tracking
 * individual lines — it was wearing {@code @Entity} while having none of an entity's properties.
 * The record makes the value semantics structural: equality by components, no mutation possible.
 */
@ValueObject
record OrderLine(Sku sku, int quantity, Money unitPrice) {

  /**
   * The most of one SKU a single line may carry.
   *
   * <p>Symmetrical with {@link Order#MAX_LINES}: how many of something a customer can order is a
   * business question, and leaving it to the width of {@code int} answers it with 2,147,483,647 — a
   * number nobody chose. It also caps the multiplication in {@link #subtotal()}, which is one of
   * the two places a monetary amount could be driven out of range (issue-00077).
   */
  static final int MAX_QUANTITY = 10_000;

  OrderLine {
    // No blank check here: Sku enforces that in its own constructor, once, for everybody
    // (issue-00085). This used to repeat it, and two copies of a rule are two rules waiting to
    // disagree.
    if (sku == null) {
      throw new DomainException("sku required");
    }
    if (quantity <= 0) {
      throw new DomainException(
          OrderingErrorCode.QUANTITY_OUT_OF_RANGE, "quantity must be > 0, was " + quantity);
    }
    if (quantity > MAX_QUANTITY) {
      throw new DomainException(
          OrderingErrorCode.QUANTITY_OUT_OF_RANGE,
          "quantity must be <= " + MAX_QUANTITY + ", was " + quantity);
    }
    if (unitPrice == null) {
      // The one unguarded component: a null price used to walk in here and NPE later in
      // subtotal(), far from the constructor that accepted it.
      throw new DomainException("unitPrice required");
    }
  }

  Money subtotal() {
    return unitPrice.times(quantity);
  }
}
