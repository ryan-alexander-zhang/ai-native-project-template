package com.acme.samples.s2.shared;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Base for aggregate roots that record domain events (Vernon, "Effective
 * Aggregate Design"; cf. Spring Modulith {@code AbstractAggregateRoot}). Kept
 * framework-free (analysis-00004): no Spring/JPA on the classpath.
 *
 * <p>The aggregate records events via {@link #registerEvent(DomainEvent)} while
 * executing behaviour; the application layer drains {@link #domainEvents()} after
 * persisting and hands them to a {@link DomainEvents} publisher, then clears them.
 */
public abstract class AggregateRoot {

    private final transient List<DomainEvent> domainEvents = new ArrayList<>();

    protected void registerEvent(DomainEvent event) {
        domainEvents.add(event);
    }

    /** Events recorded since load/creation; unmodifiable snapshot. */
    public List<DomainEvent> domainEvents() {
        return Collections.unmodifiableList(domainEvents);
    }

    public void clearDomainEvents() {
        domainEvents.clear();
    }
}
