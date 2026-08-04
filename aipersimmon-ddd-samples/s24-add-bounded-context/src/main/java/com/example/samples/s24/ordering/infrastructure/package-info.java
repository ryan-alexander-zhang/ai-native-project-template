/**
 * Ordering's persistence, over tables prefixed {@code s24_ordering_} and no others.
 *
 * <p>The order stores the coupon code it applied and the amount that came off. It does not join to
 * {@code s24_coupons_coupon} to find out whether that coupon is still valid, and the reason is not performance: what was
 * agreed at placement is a fact of this context, and re-deriving it from another context's current state would make the
 * price of a placed order change when somebody expires a coupon.
 */
package com.example.samples.s24.ordering.infrastructure;
