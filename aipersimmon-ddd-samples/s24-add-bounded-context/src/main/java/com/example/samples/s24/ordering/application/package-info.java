/**
 * Ordering's use cases, and the only layer here that knows the coupons context exists.
 *
 * <p>Exactly one class does: {@code PlaceOrderHandler}. Concentrating the knowledge is the point — on the day coupons
 * becomes a service there is one place to add a timeout, one place to decide what happens with no answer, and one place to
 * read to learn what ordering assumes about coupons.
 */
package com.example.samples.s24.ordering.application;
