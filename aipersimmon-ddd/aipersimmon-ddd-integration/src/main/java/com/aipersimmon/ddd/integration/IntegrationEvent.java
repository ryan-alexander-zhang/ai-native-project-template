package com.aipersimmon.ddd.integration;

/**
 * Marker for an integration event: a fact one bounded context publishes for
 * others to consume, part of its published language. Unlike an internal domain
 * event it is a versioned contract — carry only ids and the minimal data
 * consumers need, and evolve it backward-compatibly.
 */
public interface IntegrationEvent {
}
