package com.acme.samples.s2.ordering.application.order;

/**
 * Write port for the order read model (projection). Updated in-process from
 * domain events, in the same transaction as the aggregate change
 * (analysis-00005 §5). Kept separate from the write aggregate; a query-optimized
 * shape, not a domain-modeling artifact.
 */
public interface OrderProjection {
    void placed(String orderId, long totalMinor, String currency);
    void statusChanged(String orderId, String status);
}
