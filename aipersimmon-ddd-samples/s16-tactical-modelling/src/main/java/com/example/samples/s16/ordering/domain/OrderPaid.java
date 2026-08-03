package com.example.samples.s16.ordering.domain;

import com.aipersimmon.ddd.core.event.DomainEvent;

/** The order was paid for. */
public record OrderPaid(OrderId orderId, Money total) implements DomainEvent {}
