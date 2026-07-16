package com.example.ordering.application.order;

import com.aipersimmon.ddd.cqrs.Command;

/**
 * Ordering-internal command the saga sends to ask the inventory context to release a reservation
 * (the stock-release compensation). Its handler publishes the {@code StockReleaseRequested}
 * integration event carrying the {@code reservationId} inventory issued at reservation time. No result.
 */
public record RequestStockRelease(String orderId, String reservationId) implements Command<Void> {
}
