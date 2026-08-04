package com.example.samples.s09.ticketing.domain;

/**
 * What is true of an order. Three values, and none of them is "awaiting" anything — a wait is the
 * coordinator's business, and putting it here is how an aggregate's status starts tracking a flow's
 * progress and stops meaning anything on its own.
 */
public enum OrderStatus {
  PLACED,
  TICKETED,
  CANCELLED
}
