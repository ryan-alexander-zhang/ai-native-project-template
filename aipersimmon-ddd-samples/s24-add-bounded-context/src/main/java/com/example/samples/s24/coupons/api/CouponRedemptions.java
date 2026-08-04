package com.example.samples.s24.coupons.api;

import com.example.samples.s24.sharedkernel.api.Money;

/**
 * The other half: tell coupons that a coupon was used, after the fact.
 *
 * <p>A narrow interface rather than a published command, and the difference is worth being exact about. Exposing
 * {@code RedeemCoupon} — the command record itself — would look tidier and would publish four things nobody asked
 * for: its field names, its validation annotations, its return type, and the fact that it goes through a command
 * bus at all. A caller that holds the command is coupled to how coupons implements its own use case. A caller that
 * holds this interface is coupled to one verb.
 *
 * <p><strong>Why the caller does not send an event and let coupons subscribe.</strong> That is the more usual
 * arrangement and it is not wrong, but in this sample it would create a cycle: ordering already depends on
 * {@code coupons.api} for the quote, so coupons subscribing to {@code ordering.api} would make the two contexts
 * mutually dependent. The library's isolation rule would be perfectly happy — it checks that you go through
 * {@code api}, not that the graph is acyclic — and the build would keep compiling, because they are packages in one
 * module. It would stop compiling on the day somebody tried to make them two modules, which is the day the cycle
 * was going to matter anyway. {@code ArchitectureTest.thecontextsFormNoCycle} is the rule that catches it now
 * instead.
 *
 * <p>So the subscription lives in {@code s24.wiring}, a third place that depends on both published contracts and
 * that neither context depends on. Both directions of the integration stay one-way.
 *
 * <p><strong>Idempotency is part of this contract, not of its caller.</strong> {@code redeem} is keyed by order id
 * and a second call for the same order is a no-op. It has to be: after-commit delivery is at-least-once in any
 * arrangement worth having, and a redemption that double-counts turns a retry into a lost redemption.
 */
public interface CouponRedemptions {

  /**
   * Record that {@code code} was used for {@code orderId}, at {@code discount}.
   *
   * @return {@link Outcome#REDEEMED} the first time, {@link Outcome#ALREADY_REDEEMED} for a repeat of the same
   *     order, {@link Outcome#REFUSED} when the coupon cannot take another redemption — which is possible even
   *     though a quote accepted it, and is the honest cost of quoting without holding. The caller cannot fix a
   *     refusal; it can only make sure somebody hears about it
   */
  Outcome redeem(CouponCode code, String orderId, Money discount);

  /** What happened, as three cases the caller must tell apart. */
  enum Outcome {
    REDEEMED,
    /** The same order, again. Nothing changed and nothing is wrong. */
    ALREADY_REDEEMED,
    /**
     * The coupon would exceed its limit, or has expired since it was quoted. The order has already been placed at
     * the discounted price, so this is a discrepancy rather than a failure — and it must be visible.
     */
    REFUSED
  }
}
