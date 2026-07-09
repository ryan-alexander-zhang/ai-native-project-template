package com.aipersimmon.ddd.application;

import com.aipersimmon.ddd.core.event.DomainEvent;
import java.util.Collection;

/**
 * Port for publishing the domain events an aggregate recorded. After a use case
 * persists an aggregate it drains the aggregate's events and hands them here; the
 * infrastructure layer supplies the implementation (for example, an in-process
 * dispatcher or a transactional outbox).
 */
public interface DomainEvents {

    void publish(DomainEvent event);

    default void publishAll(Collection<? extends DomainEvent> events) {
        events.forEach(this::publish);
    }
}
