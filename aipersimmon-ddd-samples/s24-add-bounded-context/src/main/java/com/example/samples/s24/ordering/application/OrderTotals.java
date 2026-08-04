package com.example.samples.s24.ordering.application;

/**
 * What the caller gets back, including why their coupon did not apply.
 *
 * <p>{@code couponRefusal} is the field that makes the synchronous choice worth its cost. The customer typed a code and
 * is owed an answer in the same breath as the price — a design where they learn by comparing two numbers is a design that
 * generates support tickets.
 *
 * @param couponCode the code that was applied, or null when none was
 * @param couponRefusal why the code did not apply, or null when it did or none was given
 */
public record OrderTotals(
    String orderId,
    long grossMinor,
    long discountMinor,
    long totalMinor,
    String currency,
    String couponCode,
    String couponRefusal) {}
