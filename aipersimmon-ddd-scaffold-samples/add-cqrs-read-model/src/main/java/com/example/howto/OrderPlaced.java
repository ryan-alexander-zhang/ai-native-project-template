package com.example.howto;

import com.aipersimmon.ddd.core.event.DomainEvent;

/** Domain event recorded when an order is placed; drives the read-model projection. */
public record OrderPlaced(String orderId, String sku) implements DomainEvent {
}
