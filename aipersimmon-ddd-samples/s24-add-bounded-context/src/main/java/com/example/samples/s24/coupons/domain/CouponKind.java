package com.example.samples.s24.coupons.domain;

/**
 * How a coupon computes its discount. Private to this context, deliberately.
 *
 * <p>It is the most tempting thing to publish and the worst. A caller holding this enum would compute the discount
 * itself — that is what an enum of arithmetic kinds invites — and then rounding, caps, and the next kind added would
 * all be changes to two contexts at once. {@code CouponQuote} publishes the <em>answer</em> instead, which is why a
 * third kind can be added here without anybody being told.
 */
enum CouponKind {
  /** A percentage off, rounded down. */
  PERCENT,
  /** A fixed amount off, never more than the amount it is applied to. */
  FIXED
}
