package com.example.samples.s01.ordering.domain;

import com.aipersimmon.ddd.core.event.DomainEvent;

/** Registered by {@link Order#place}; published by the repository when the order is saved. */
public record OrderPlaced(OrderId orderId, String customerId) implements DomainEvent {}
