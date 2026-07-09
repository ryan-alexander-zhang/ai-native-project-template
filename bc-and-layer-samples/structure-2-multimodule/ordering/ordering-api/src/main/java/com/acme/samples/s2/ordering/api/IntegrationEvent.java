package com.acme.samples.s2.ordering.api;

/**
 * Marker for Ordering's integration events — the published language other contexts
 * may depend on. Used by the outbox publisher to route each event to its topic.
 */
public interface IntegrationEvent {
}
