/**
 * The coupons model, and everything in it is private to this context.
 *
 * <p>{@code Coupon}, {@code Coupons} and {@code CouponKind} are the three things most likely to be published by a
 * team in a hurry, and each of them is here for a stated reason. The mechanical guarantee is on the other side: no
 * class in this package depends on another context, not even on a published contract, which is what makes the context
 * liftable and is checked by {@code ArchitectureTest.nodomainKnowsAnotherContextExists}.
 */
package com.example.samples.s24.coupons.domain;
