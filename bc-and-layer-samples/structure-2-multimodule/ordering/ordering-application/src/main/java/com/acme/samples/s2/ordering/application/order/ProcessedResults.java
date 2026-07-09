package com.acme.samples.s2.ordering.application.order;

/**
 * Inbox port — idempotent consumer (analysis-00005 §4 / gap G4; Richardson,
 * microservices.io). Records which orders' stock results have already been
 * applied so a redelivered {@code stock-result} message converges instead of
 * driving the aggregate state machine twice.
 */
public interface ProcessedResults {
    boolean alreadyApplied(String orderId);
    void markApplied(String orderId);
}
