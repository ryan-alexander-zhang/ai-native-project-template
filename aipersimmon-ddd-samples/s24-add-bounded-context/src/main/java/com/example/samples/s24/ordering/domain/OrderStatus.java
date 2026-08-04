package com.example.samples.s24.ordering.domain;

/**
 * Ordering's own vocabulary, and a good example of what must <strong>not</strong> go in the shared kernel.
 *
 * <p>It is tempting: coupons might one day want to know whether an order was cancelled. But sharing it would mean
 * ordering could not add a status without asking, and a status is the thing a lifecycle grows. If coupons needs to know
 * about a cancellation, ordering publishes a fact about it — a cancellation is an event, not an enum.
 */
public enum OrderStatus {
  PLACED,
  CANCELLED
}
