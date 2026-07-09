package com.example.ordering.application.order;

import com.aipersimmon.ddd.core.event.DomainEvent;
import java.util.Collection;

/**
 * Port for publishing the domain events an aggregate recorded. The application
 * drains an aggregate's events after saving it and hands them here; the
 * infrastructure layer supplies the implementation.
 */
public interface DomainEvents {

    void publish(DomainEvent event);

    default void publishAll(Collection<? extends DomainEvent> events) {
        events.forEach(this::publish);
    }
}
