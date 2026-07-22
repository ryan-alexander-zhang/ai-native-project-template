package com.aipersimmon.ddd.integration;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the logical event type (CloudEvents {@code type}) of an {@link IntegrationEvent}. It is
 * <strong>required</strong> on every integration event: {@link IntegrationEvent#eventTypeOf(Class)}
 * reads it and fails if it is absent, and the consumer-side type registry keys off the same value —
 * so a producer's wire {@code type} and a consumer's lookup key always agree. There is no
 * class-name default, because a Java class name is an implementation detail, not a stable published
 * contract.
 *
 * <p>Declaring it as an annotation (rather than overriding {@code eventType()} as a method) is what
 * lets it be read from the class alone, without constructing an instance — so the registry can be
 * built by scanning classes. A method override would be invisible to that scan.
 *
 * <pre>{@code
 * @EventType(name = "com.example.ordering.OrderPlaced", version = 1)
 * public record OrderPlaced(String orderId) implements IntegrationEvent { }
 * }</pre>
 *
 * <p>{@link #name()} identifies the business event; {@link #version()} is its payload schema
 * revision (the CloudEvents {@code dataschemaversion}). Together, {@code (name, version)} is the
 * <strong>exact</strong> wire resolution key — the value a producer stamps and the value a
 * consumer's {@link IntegrationEventCatalog} is keyed by. When the payload schema changes, bump
 * {@code version}; when the business fact the event asserts changes, use a new {@code name}.
 * Because resolution is exact, each {@code (name, version)} is a distinct contract: the default
 * scan registers one annotated class per pair, so to keep consuming (or replaying) an older version
 * after a newer one ships, keep the older version's class on the consumer — or provide a catalogue
 * mapping. A custom {@link IntegrationEventCatalog} may map several versions to one
 * backward-compatible class, but there is no implicit fall back across versions (an unregistered
 * pair dead-letters). The structured {@code version} also gives tooling a field to lint (e.g. a
 * schema change without a version bump).
 *
 * <p>{@code name} must be non-blank and should be stable and namespaced so it survives refactors
 * and language boundaries; {@code version} must be {@code >= 1}. A single consumer must not have
 * two classes registered under the same {@code (name, version)} — the registry rejects that clash
 * (one would otherwise shadow the other).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface EventType {

  /**
   * The stable logical event type (CloudEvents {@code type}), e.g. {@code
   * "com.example.ordering.OrderPlaced"} — with no version suffix; the version is {@link
   * #version()}.
   */
  String name();

  /**
   * The payload schema revision (CloudEvents {@code dataschemaversion}), starting at 1 and part of
   * the exact {@code (name, version)} resolution key. Bump it on any payload schema change; a
   * change to the business fact the event asserts is a new {@link #name()} instead.
   */
  int version();
}
