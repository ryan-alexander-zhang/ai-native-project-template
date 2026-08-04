package com.example.samples.s24.coupons.api;

import com.example.samples.s24.sharedkernel.api.Money;

/**
 * What a coupon is worth against a given amount, right now — or why it is worth nothing.
 *
 * <p>A record of two facts and a reason, and each of the three is here because leaving it out forces the caller to
 * guess:
 *
 * <ul>
 *   <li>{@code discount} — the number ordering needs to price the order. Not a percentage, not a rule: an amount,
 *       already applied to the amount that was asked about. Handing back a percentage would make ordering implement
 *       coupons' arithmetic, and then a change to how a percentage rounds would be a change to two contexts.
 *   <li>{@code accepted} — whether the coupon applies. Separate from a zero discount, because a coupon that is
 *       valid and worth nothing on this basket is a different answer from a coupon that has expired.
 *   <li>{@code reason} — why not, in a form the caller may show. A refusal with no reason turns "why was my coupon
 *       rejected" into a support ticket.
 * </ul>
 *
 * <p>What is deliberately absent: the coupon. No kind, no percentage, no validity window, no redemption count.
 * Ordering does not need them to price an order, and every one of them would be a detail coupons could no longer
 * change without a conversation. <strong>A published contract is the smallest thing that answers the question
 * asked</strong> — not a projection of the model.
 *
 * @param code the coupon this is about
 * @param accepted whether it applies at all
 * @param discount how much comes off; zero when not accepted
 * @param reason why it does not apply, or null when it does
 */
public record CouponQuote(CouponCode code, boolean accepted, Money discount, String reason) {

  public static CouponQuote accepted(CouponCode code, Money discount) {
    if (discount.isNegative()) {
      throw new IllegalArgumentException("a discount must not be negative, was " + discount);
    }
    return new CouponQuote(code, true, discount, null);
  }

  public static CouponQuote refused(CouponCode code, String currency, String reason) {
    return new CouponQuote(code, false, Money.zero(currency), reason);
  }
}
