package com.example.samples.s18.ordering.domain;

import com.aipersimmon.ddd.core.event.DomainEvent;

/** The in-process fact. Named to keep it distinct from the integration event of the same occurrence. */
public record OrderPlacedInContext(OrderId orderId, String customerId, long amountCents)
    implements DomainEvent {}
