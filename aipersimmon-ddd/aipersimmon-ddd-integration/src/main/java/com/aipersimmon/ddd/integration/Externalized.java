package com.aipersimmon.ddd.integration;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Opts an {@link IntegrationEvent} in to <strong>external</strong> transport: the event
 * is routed out to the named broker target ({@link #value()}) instead of being delivered
 * only in-process. An event <em>without</em> this annotation stays LOCAL — the default —
 * and never reaches the broker; externalization is strictly opt-in, event by event, so
 * installing a messaging transport does not silently put every event on the wire.
 *
 * <p>This is deliberately a <strong>separate</strong> annotation from {@link EventType},
 * even though both sit on the event class. {@code @EventType} declares the event's
 * <em>contract identity</em> — a stable, language-neutral logical type — and must not be
 * polluted by transport or deployment concerns. {@code @Externalized} declares two things
 * along a contract/deployment split:
 *
 * <ul>
 *   <li><strong>Whether to externalize</strong> is a contract-level fact: this event is
 *       part of another process's diet, not merely an internal cross-module signal. That
 *       fact is the presence of this annotation.
 *   <li><strong>Where it goes</strong> is a deployment detail: the {@link #value()} target
 *       is the logical broker destination (for Kafka, a topic name). It may be a literal
 *       (e.g. {@code "ordering.events"}) or a {@code ${property}} placeholder (e.g.
 *       {@code "${ordering.topic:ordering.events}"}) so the concrete destination lives in
 *       configuration; placeholder resolution happens in the assembly layer, not here.
 * </ul>
 *
 * <pre>{@code
 * @EventType(name = "com.example.ordering.OrderPlaced", version = 1)
 * @Externalized("ordering.events")           // omit this line -> LOCAL only
 * public record OrderPlaced(String orderId) implements IntegrationEvent { }
 * }</pre>
 *
 * <p>Promoting an event from LOCAL to EXTERNAL is therefore adding this one annotation and
 * a topic name — the publisher (which only calls the {@code IntegrationEvents} port) and
 * every {@code @EventListener} handler stay unchanged. Because a local delivery of an
 * externalized event comes back through the consumer bridge, an event must be externalized
 * once (it has a single delivery path), not delivered both ways.
 *
 * @see IntegrationEvent#externalizedTarget(Class)
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Externalized {

    /**
     * The logical broker target this event is routed to — for Kafka, the topic name.
     * Must be non-blank. May be a literal or contain {@code ${property:default}}
     * placeholders resolved by the messaging assembly against the environment, so the
     * concrete destination is a deployment detail rather than baked into the contract.
     */
    String value();
}
