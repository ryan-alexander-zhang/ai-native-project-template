package com.example.samples.s01.ordering.domain;

/** The states an order can be in. Which transitions are legal is declared on {@link Order}. */
public enum OrderStatus {
  PLACED,
  CONFIRMED
}
