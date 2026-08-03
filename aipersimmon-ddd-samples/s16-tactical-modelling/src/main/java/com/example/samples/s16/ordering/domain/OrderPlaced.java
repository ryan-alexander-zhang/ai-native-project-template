package com.example.samples.s16.ordering.domain;

import com.aipersimmon.ddd.core.event.DomainEvent;

/** Registered inside the behaviour that caused it; published by the repository after the save. */
public record OrderPlaced(OrderId orderId, CustomerId customerId, Money total)
    implements DomainEvent {}
