package com.example.samples.s24.coupons.application;

import com.example.samples.s24.coupons.api.CouponCode;
import com.example.samples.s24.coupons.api.CouponQuote;
import com.example.samples.s24.coupons.api.CouponQuotes;
import com.example.samples.s24.coupons.domain.Coupon;
import com.example.samples.s24.coupons.domain.Coupons;
import com.example.samples.s24.sharedkernel.api.Money;
import java.time.Clock;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The published question, answered — and the class where the boundary is actually implemented.
 *
 * <p>Three decisions worth reading.
 *
 * <p><strong>It is in {@code application}, not {@code api}.</strong> The interface is the contract; this is one way of
 * satisfying it. After the split there will be two implementations of {@code CouponQuotes} — an HTTP client on
 * ordering's side and something like this class on the other — and only the interface will be shared.
 *
 * <p><strong>It never throws for a refusal.</strong> An unknown code, an expired one, an exhausted one: all three come
 * back as {@code accepted = false} with a reason. Ordering has a branch for "no discount" and does not have a
 * {@code catch}. That matters more than it looks after the split, because the branch that has to grow — timeouts,
 * unavailability — is a branch that already exists.
 *
 * <p><strong>It is read-only, and says so to the transaction manager too.</strong> {@code SUPPORTS} + {@code readOnly}
 * rather than {@code REQUIRED}: quoting must not open a transaction of its own, and must not be the reason one exists.
 * It joins the caller's if there is one, which today there always is — the caller is a command handler. That is a
 * latent coupling rather than a bug, and it is named: {@code SplittingOutTest} measures that the call happens inside
 * ordering's write transaction, which is exactly what must change on the day this becomes a network hop.
 */
@Service
class QuoteCoupons implements CouponQuotes {

  private final Coupons coupons;
  private final Clock clock;

  QuoteCoupons(Coupons coupons, Clock clock) {
    this.coupons = coupons;
    this.clock = clock;
  }

  @Override
  @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
  public CouponQuote quote(CouponCode code, Money amount) {
    Optional<Coupon> found = coupons.find(code);
    if (found.isEmpty()) {
      // Not a 404 and not an exception: "we do not know that code" is an answer to the question asked.
      return CouponQuote.refused(code, amount.currency(), "we do not recognise this coupon code");
    }
    Coupon coupon = found.get();
    Optional<String> refusal = coupon.refusalAt(clock.instant());
    if (refusal.isPresent()) {
      return CouponQuote.refused(code, amount.currency(), refusal.get());
    }
    if (!coupon.currency().equals(amount.currency())) {
      return CouponQuote.refused(
          code, amount.currency(), "this coupon cannot be used in " + amount.currency());
    }
    return CouponQuote.accepted(code, coupon.discountOn(amount));
  }
}
