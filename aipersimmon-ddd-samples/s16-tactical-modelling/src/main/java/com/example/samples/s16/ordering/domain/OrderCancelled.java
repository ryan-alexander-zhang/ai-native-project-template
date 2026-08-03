package com.example.samples.s16.ordering.domain;

import com.aipersimmon.ddd.core.event.DomainEvent;

/** The order was cancelled before it could be paid. */
public record OrderCancelled(OrderId orderId) implements DomainEvent {}
