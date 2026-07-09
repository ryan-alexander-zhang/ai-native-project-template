package com.acme.samples.s2.shared;

import java.util.Collection;

/**
 * In-process domain-event publisher port (analysis-00001). The application layer
 * depends only on this interface; infrastructure supplies a pluggable
 * implementation (a real forwarder wrapped by cross-cutting decorators).
 *
 * <p>Default semantics are <b>synchronous, same-thread, same-transaction</b>
 * (analysis-00001 §2): the caller is inside {@code @Transactional}, so
 * {@code @EventListener} handlers run inline and commit atomically with the
 * aggregate. This is required for the transactional-outbox translation step
 * (analysis-00005 §3) — do not make it async here.
 */
public interface DomainEvents {

    void publish(DomainEvent event);

    default void publish(Collection<? extends DomainEvent> events) {
        events.forEach(this::publish);
    }
}
