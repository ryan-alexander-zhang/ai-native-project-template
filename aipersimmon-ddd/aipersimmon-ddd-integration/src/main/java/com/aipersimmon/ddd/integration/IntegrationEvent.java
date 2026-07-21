package com.aipersimmon.ddd.integration;

import java.util.Optional;

/**
 * Marker for an integration event: a fact one bounded context publishes for
 * others to consume, part of its published language. Unlike an internal domain
 * event it is a versioned contract — carry only ids and the minimal data
 * consumers need, and evolve it backward-compatibly.
 *
 * <p>The published contract's identity — its logical type and schema version — is
 * declared with the {@link EventType} annotation and read statically via
 * {@link #eventTypeOf} / {@link #eventVersionOf}, so the transport never reaches into
 * the Java class and there is no instance method to override out of sync. It is
 * <strong>required</strong>: there is no class-name default, because a Java class name
 * is an implementation detail, not a published contract.
 *
 * <p>{@link #subject()} — the CloudEvents {@code subject} — is the one genuinely
 * per-instance value declared here: the id of the aggregate the event is about, used
 * as the transport partition/ordering key so one aggregate's events stay in order.
 */
public interface IntegrationEvent {

    /**
     * The logical event type declared by {@code type}, read statically from its
     * {@link EventType} annotation's {@code name}. This static reader is the
     * <strong>single source of truth</strong> for an event's logical type — there is
     * deliberately no instance {@code eventType()} method to override, so the value a
     * producer stamps on the wire and the value a consumer's catalogue is keyed by
     * cannot drift apart. There is likewise no fallback to the simple class name: the
     * logical type is a published contract that must be declared explicitly, so an
     * unannotated event is a hard error rather than a silent — and unstable —
     * derivation from the class name.
     *
     * @throws IllegalStateException if {@code type} has no {@link EventType}
     *     annotation, or its {@code name} is blank
     */
    static String eventTypeOf(Class<?> type) {
        EventType annotation = requireEventType(type);
        if (annotation.name().isBlank()) {
            throw new IllegalStateException(type.getName()
                    + " must declare a non-blank @EventType name, e.g. "
                    + "@EventType(name = \"com.example.ordering.OrderPlaced\", version = 1); the Java class "
                    + "name is an implementation detail, not a stable published contract");
        }
        return annotation.name();
    }

    /**
     * The schema revision declared by {@code type}, read statically from its
     * {@link EventType} annotation's {@code version}. Like {@link #eventTypeOf} it is
     * the single source of truth (no overridable instance method), so a producer's
     * stamped version and a consumer's catalogue key cannot drift apart.
     *
     * @throws IllegalStateException if {@code type} has no {@link EventType}
     *     annotation, or its {@code version} is not {@code >= 1}
     */
    static int eventVersionOf(Class<?> type) {
        EventType annotation = requireEventType(type);
        if (annotation.version() < 1) {
            throw new IllegalStateException(type.getName()
                    + " must declare an @EventType version >= 1 (got " + annotation.version() + ")");
        }
        return annotation.version();
    }

    /**
     * The external transport target declared by {@code type}'s {@link Externalized}
     * annotation, or {@link Optional#empty()} if it has none — in which case the
     * event is LOCAL and never reaches the broker. Read statically from the class (like
     * {@link #eventTypeOf}) so routing can be decided without constructing an instance and
     * a consumer-side scan can build the reach map from the class alone. The returned value
     * is the <em>raw</em> target, which may still contain {@code ${property}} placeholders;
     * resolving those against configuration is the assembly layer's job.
     *
     * @throws IllegalStateException if the annotation is present but its {@code value} is
     *     blank — an externalized event must name a target
     */
    static Optional<String> externalizedTarget(Class<?> type) {
        Externalized annotation = type.getAnnotation(Externalized.class);
        if (annotation == null) {
            return Optional.empty();
        }
        if (annotation.value().isBlank()) {
            throw new IllegalStateException(type.getName()
                    + " is @Externalized but declares a blank target; name the broker target, e.g. "
                    + "@Externalized(\"ordering.events\") or @Externalized(\"${ordering.topic:ordering.events}\")");
        }
        return Optional.of(annotation.value());
    }

    private static EventType requireEventType(Class<?> type) {
        EventType annotation = type.getAnnotation(EventType.class);
        if (annotation == null) {
            throw new IllegalStateException(type.getName()
                    + " must be annotated with @EventType, e.g. "
                    + "@EventType(name = \"com.example.ordering.OrderPlaced\", version = 1); the logical event "
                    + "type is a published contract that must be declared explicitly");
        }
        return annotation;
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
