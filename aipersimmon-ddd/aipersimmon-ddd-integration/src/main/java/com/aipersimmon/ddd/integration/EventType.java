package com.aipersimmon.ddd.integration;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the logical event type (CloudEvents {@code type}) of an
 * {@link IntegrationEvent}. It is <strong>required</strong> on every integration
 * event: {@link IntegrationEvent#eventType()} reads it and fails if it is absent,
 * and the consumer-side type registry keys off the same value — so a producer's wire
 * {@code type} and a consumer's lookup key always agree. There is no class-name
 * default, because a Java class name is an implementation detail, not a stable
 * published contract.
 *
 * <p>Declaring it as an annotation (rather than overriding {@code eventType()} as a
 * method) is what lets it be read from the class alone, without constructing an
 * instance — so the registry can be built by scanning classes. A method override
 * would be invisible to that scan.
 *
 * <pre>{@code
 * @EventType(name = "com.example.ordering.OrderPlaced", version = 1)
 * public record OrderPlaced(String orderId) implements IntegrationEvent { }
 * }</pre>
 *
 * <p>{@link #name()} is the stable identity — the CloudEvents {@code type} on the
 * wire and the consumer registry's lookup key. {@link #version()} is a separate
 * schema-revision counter (the CloudEvents {@code dataschemaversion}), <em>not</em>
 * part of the identity: {@code v1} and {@code v2} of one {@code name} resolve to the
 * same class, so evolve compatibly (add optional fields) and bump {@code version}. A
 * <em>breaking</em> change is a new {@code name} (a distinct type/class), never a
 * version bump. Keeping the version out of the name is what lets one handler serve
 * every compatible revision, and gives tooling a structured field to lint (e.g. a
 * schema change without a version bump).
 *
 * <p>{@code name} must be non-blank and should be stable and namespaced so it
 * survives refactors and language boundaries; {@code version} must be {@code >= 1}.
 * {@code name} must be unique across the events a single consumer resolves — the
 * registry rejects two classes that declare the same name.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface EventType {

    /**
     * The stable logical event type (CloudEvents {@code type}), e.g.
     * {@code "com.example.ordering.OrderPlaced"} — with no version suffix; the version
     * is {@link #version()}.
     */
    String name();

    /**
     * The schema revision (CloudEvents {@code dataschemaversion}), starting at 1.
     * Bump it on a backward-compatible change; a breaking change is a new
     * {@link #name()} instead.
     */
    int version();
}
