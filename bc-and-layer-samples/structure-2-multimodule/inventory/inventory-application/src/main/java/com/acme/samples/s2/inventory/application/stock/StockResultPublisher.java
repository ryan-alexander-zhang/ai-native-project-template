package com.acme.samples.s2.inventory.application.stock;

import com.acme.samples.s2.inventory.api.StockResult;

/**
 * Driven port: hand off the {@link StockResult} integration event for reliable
 * delivery. The infrastructure adapter writes it to a transactional outbox in the
 * SAME transaction as the reservation, so the return leg of the saga cannot be
 * lost (analysis-00005 §4). Symmetric to Ordering's {@code IntegrationEvents} outbox.
 */
public interface StockResultPublisher {
    void publish(StockResult result);
}
