package com.example.samples.s03.ordering.domain;

import com.aipersimmon.ddd.core.event.DomainEvent;

/**
 * What happened, stated in the context's own language.
 *
 * <p>It carries identities and the few facts a reaction needs — not the {@code Order} itself. Passing
 * the aggregate would let a subscriber mutate the instance that was just persisted, which the publish
 * guard refuses; and it would tie every subscriber to the root's shape.
 */
public record OrderPlaced(OrderId orderId, String customerId, boolean firstOrder, long amountCents)
    implements DomainEvent {}
