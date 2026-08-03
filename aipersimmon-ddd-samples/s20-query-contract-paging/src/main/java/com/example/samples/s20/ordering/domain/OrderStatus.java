package com.example.samples.s20.ordering.domain;

/** An order's lifecycle. Two states are enough: the list filters on one of them. */
public enum OrderStatus {
  PLACED,
  CONFIRMED
}
