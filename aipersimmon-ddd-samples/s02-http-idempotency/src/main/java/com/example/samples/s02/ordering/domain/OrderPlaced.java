package com.example.samples.s02.ordering.domain;

import com.aipersimmon.ddd.core.event.DomainEvent;

/** Published by the repository once the order is committed. */
public record OrderPlaced(OrderId orderId, ClientReference clientReference, long amountCents)
    implements DomainEvent {}
