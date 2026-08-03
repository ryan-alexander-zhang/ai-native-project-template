package com.example.samples.s16.ordering.domain;

import com.aipersimmon.ddd.core.annotation.Service;

/**
 * A domain service — the library's {@code @Service}, not Spring's.
 *
 * <p>It is here because the calculation belongs to neither object it needs: putting it on
 * {@code Order} would make an order know about the loyalty programme, and putting it on the customer
 * would make the customer know how an order is totalled. Stateless, operates only on domain objects,
 * touches no repository — which is why it can live in a module that has no framework on its classpath.
 */
@Service
public final class LoyaltyDiscount {

  public Money discountFor(Order order, LoyaltyTier tier) {
    return order.total().percent(tier.discountPercent());
  }
}
