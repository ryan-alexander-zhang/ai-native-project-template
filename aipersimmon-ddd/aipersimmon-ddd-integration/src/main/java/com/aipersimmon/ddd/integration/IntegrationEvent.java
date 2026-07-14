package com.aipersimmon.ddd.integration;

/**
 * Marker for an integration event: a fact one bounded context publishes for
 * others to consume, part of its published language. Unlike an internal domain
 * event it is a versioned contract — carry only ids and the minimal data
 * consumers need, and evolve it backward-compatibly.
 *
 * <p>Two aspects of the published contract are declared here so the transport never
 * has to reach into the Java class:
 * <ul>
 *   <li>{@link #eventType()} — the CloudEvents {@code type}: a stable, logical name
 *       for the event, decoupled from the Java class name so a consumer maps it to
 *       its own local type rather than depending on the producer's class.
 *   <li>{@link #subject()} — the CloudEvents {@code subject}: the id of the aggregate
 *       the event is about, used as the transport partition/ordering key so one
 *       aggregate's events stay in order.
 * </ul>
 */
public interface IntegrationEvent {

    /**
     * The logical event type (CloudEvents {@code type}). Defaults to the simple
     * class name; override to a versioned, namespaced name that is stable across
     * refactors and language boundaries — for example
     * {@code "com.example.ordering.OrderPlaced.v1"}.
     */
    default String eventType() {
        return getClass().getSimpleName();
    }

    /**
     * The id of the aggregate this event is about (CloudEvents {@code subject}),
     * used as the transport partition/ordering key. Return {@code null} when the
     * event has no natural ordering key (delivery then falls back to the event id,
     * which does not preserve per-aggregate order).
     */
    default String subject() {
        return null;
    }
}
