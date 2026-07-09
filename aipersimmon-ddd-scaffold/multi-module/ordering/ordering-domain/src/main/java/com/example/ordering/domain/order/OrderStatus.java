package com.example.ordering.domain.order;

/** Lifecycle states of an {@link Order}. Legal transitions are guarded in Order. */
public enum OrderStatus {
    PENDING,
    CONFIRMED,
    CANCELLED
}
