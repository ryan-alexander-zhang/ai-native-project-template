/**
 * The coupons context's own HTTP edge — one endpoint, because a coupon is issued by an operator.
 *
 * <p>Deliberately not a door onto quoting or redeeming: both of those are steps inside somebody else's use case, and a
 * second way to reach a rule is the way that skips a step.
 */
package com.example.samples.s24.coupons.interfaces;
