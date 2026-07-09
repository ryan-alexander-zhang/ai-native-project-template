package com.acme.samples.s2.ordering.domain.order;

import com.acme.samples.s2.shared.DomainEvent;
import com.acme.samples.s2.shared.Money;

import java.util.List;

/**
 * Domain event (in-process, rich): an {@link Order} was placed. Part of
 * Ordering's ubiquitous language and free to carry internal domain types
 * ({@link OrderLineData}, {@link Money}). It is NOT the cross-context contract —
 * an application handler translates it to the thin integration event
 * {@code ordering.api.OrderPlaced} (analysis-00002 / analysis-00005 §3).
 */
public record OrderPlacedEvent(String orderId, String customerId,
                               List<OrderLineData> lines, Money total) implements DomainEvent {
}
