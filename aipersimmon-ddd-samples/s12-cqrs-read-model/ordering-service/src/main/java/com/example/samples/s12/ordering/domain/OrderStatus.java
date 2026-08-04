package com.example.samples.s12.ordering.domain;

/** Where an order stands. Short on purpose: S12 is about the read side, not about order lifecycles. */
public enum OrderStatus {
  PLACED,
  PAID
}
