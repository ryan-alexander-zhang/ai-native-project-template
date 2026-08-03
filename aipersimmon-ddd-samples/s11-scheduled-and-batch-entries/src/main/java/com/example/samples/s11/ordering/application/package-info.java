/**
 * The use cases, and the batch that drives one of them in a loop.
 *
 * <p>{@code ExpiredOrderSweep} is the interesting one: it is the work a schedule triggers, with no
 * {@code @Scheduled} on it, so the timer, an operator's endpoint and a test all reach the same code.
 */
package com.example.samples.s11.ordering.application;
