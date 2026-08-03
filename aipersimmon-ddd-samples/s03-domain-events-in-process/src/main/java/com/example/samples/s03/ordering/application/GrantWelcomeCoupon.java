package com.example.samples.s03.ordering.application;

import com.aipersimmon.ddd.application.DomainEventHandler;
import com.aipersimmon.ddd.core.id.IdGenerator;
import com.example.samples.s03.ordering.domain.OrderPlaced;
import com.example.samples.s03.rewards.domain.Coupon;
import com.example.samples.s03.rewards.domain.CouponId;
import com.example.samples.s03.rewards.domain.Coupons;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * A reaction that belongs in the same transaction: if the coupon cannot be granted, the order should
 * not exist either — the two are one business outcome here.
 *
 * <p>{@code @EventListener} (not {@code @TransactionalEventListener}) is what puts it there: publishing
 * happens inside the command's transaction, on the caller's thread, so this runs before the commit and
 * a failure takes the whole command down with it. The sample proves that rather than asserting it.
 *
 * <p>Whether two aggregates in one transaction is acceptable is a separate question — S8. What matters
 * here is that the phase decides it, and choosing the wrong phase changes the outcome silently.
 */
@Component
@DomainEventHandler
class GrantWelcomeCoupon {

  private static final long WELCOME_VALUE_CENTS = 500;

  private final Coupons coupons;
  private final IdGenerator idGenerator;

  GrantWelcomeCoupon(Coupons coupons, IdGenerator idGenerator) {
    this.coupons = coupons;
    this.idGenerator = idGenerator;
  }

  @EventListener
  void on(OrderPlaced event) {
    if (!event.firstOrder()) {
      return;
    }
    if (event.customerId().startsWith("poison")) {
      // Stands in for a real failure inside the reaction: a rule the coupon context refuses, a
      // constraint violation, a bug. The test asserts what happens to the order when this throws.
      throw new IllegalStateException("the rewards context refused " + event.customerId());
    }
    coupons.save(
        Coupon.welcome(
            new CouponId(idGenerator.newId()), event.customerId(), WELCOME_VALUE_CENTS));
  }
}
