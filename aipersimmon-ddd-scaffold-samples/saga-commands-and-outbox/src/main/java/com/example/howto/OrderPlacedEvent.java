package com.example.howto;

import com.aipersimmon.ddd.core.event.DomainEvent;

/** In-process domain event announcing an order was placed; starts the fulfilment saga. */
public record OrderPlacedEvent(String orderId, String sku) implements DomainEvent {
}
