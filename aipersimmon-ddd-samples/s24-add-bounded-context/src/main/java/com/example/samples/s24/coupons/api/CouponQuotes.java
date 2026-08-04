package com.example.samples.s24.coupons.api;

import com.example.samples.s24.sharedkernel.api.Money;

/**
 * The synchronous half of the boundary: ask what a coupon is worth, get an answer, decide.
 *
 * <p><strong>Why this one is synchronous and the redemption is not.</strong> The catalogue asks whether a new
 * context's first integration should be an event or a call, and the useful form of the question is not "which is
 * more modern" — it is <em>whether the answer is needed to decide, or only to record</em>:
 *
 * <ul>
 *   <li>An order cannot be priced without knowing the discount. The answer is an input to a decision the caller is
 *       making right now, so it has to be a call. There is no version of this that an event can do — a message
 *       arriving later cannot inform a choice already made.
 *   <li>A redemption is a consequence of a decision already taken. Nobody is waiting for it, and it must not be able
 *       to fail the order. So it is an event, after commit. See {@link CouponRedemptions}.
 * </ul>
 *
 * <p><strong>Read-only, and that is part of the contract.</strong> Quoting must not consume anything: a customer
 * changing their basket four times would burn four redemptions of a single-use coupon. {@code QuoteTest} asserts the
 * redemption count is untouched by a quote — which also means the quote's answer is <em>advisory</em>, and the real
 * limit check happens at redemption. What that costs is measured, not hidden: see the analysis document's §6.
 *
 * <p><strong>An interface, because it is the seam.</strong> Today the implementation is a bean in the same JVM and
 * the call cannot time out, cannot be unavailable, and cannot fail halfway. After the context becomes its own
 * service, all three become possible, and the thing that has to change is not this interface — it is the
 * <em>caller's</em> handling of a missing answer. Which is why the contract is shaped for remoteness now:
 * {@code quote} answers with a refusal rather than throwing, so ordering already has a branch for "no discount",
 * and the branch that will have to grow is one that exists.
 *
 * <p>One thing this interface does not hide, and the sample says so rather than pretending: the call currently
 * happens inside the caller's write transaction, because in-process it is free. Across a network it would be a
 * remote call holding a database transaction open, which is the shape nobody wants. {@code SplittingOutTest}
 * measures that the call is inside a transaction today — the first thing to change on the day it moves.
 */
public interface CouponQuotes {

  /**
   * What {@code code} is worth against {@code amount} at this moment.
   *
   * @param code the coupon presented
   * @param amount the amount to discount, in the currency the answer will be in
   * @return an accepted quote carrying the discount, or a refusal carrying a reason. Never null, and never an
   *     exception for an ordinary refusal — an unknown or expired coupon is a normal answer to a normal question
   */
  CouponQuote quote(CouponCode code, Money amount);
}
