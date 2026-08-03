package com.example.samples.s16.ordering.domain;

/** The states an order moves through. Which moves are legal is declared on {@link Order}. */
public enum OrderStatus {
  DRAFT,
  PLACED,
  PAID,
  CANCELLED
}
