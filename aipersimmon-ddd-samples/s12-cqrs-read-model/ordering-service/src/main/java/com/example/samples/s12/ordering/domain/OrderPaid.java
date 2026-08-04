package com.example.samples.s12.ordering.domain;

import com.aipersimmon.ddd.core.event.DomainEvent;

/** An order was paid. Same shape and same reasoning as {@link OrderPlaced}. */
public record OrderPaid(OrderId orderId, String customerId) implements DomainEvent {}
