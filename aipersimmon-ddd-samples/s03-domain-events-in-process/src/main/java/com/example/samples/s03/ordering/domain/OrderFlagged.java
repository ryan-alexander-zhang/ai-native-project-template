package com.example.samples.s03.ordering.domain;

import com.aipersimmon.ddd.core.event.DomainEvent;

/** Registered by {@link Order#flagForReview}. */
public record OrderFlagged(OrderId orderId, String reason) implements DomainEvent {}
