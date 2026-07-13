package com.aipersimmon.ddd.core.model;

import com.aipersimmon.ddd.core.event.DomainEvent;
import com.aipersimmon.ddd.core.rule.BusinessRule;
import com.aipersimmon.ddd.core.rule.BusinessRuleViolationException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Base class for aggregate roots that record domain events while executing
 * behaviour. During a use case the root registers events via
 * {@link #registerEvent(DomainEvent)}; after the aggregate is persisted the
 * application drains {@link #domainEvents()}, publishes them, and then calls
 * {@link #clearDomainEvents()}.
 *
 * <p>Framework-free: it records events in memory and takes no stance on how they
 * are published. Subclasses supply the aggregate's identity via {@link #id()}.
 *
 * @param <ID> the identity type of the root
 */
public abstract class AbstractAggregateRoot<ID> implements AggregateRoot<ID> {

    private final transient List<DomainEvent> domainEvents = new ArrayList<>();

    /** Record a domain event to be published after the aggregate is persisted. */
    protected void registerEvent(DomainEvent event) {
        domainEvents.add(event);
    }

    /**
     * Enforce a business invariant from inside an intention-revealing method: throw a
     * {@link BusinessRuleViolationException} if {@code rule} is broken, otherwise do
     * nothing. Prefer this over inline {@code if (...) throw} so invariants stay named
     * and reusable.
     */
    protected void checkRule(BusinessRule rule) {
        if (rule.isBroken()) {
            throw new BusinessRuleViolationException(rule);
        }
    }

    /** An unmodifiable snapshot of the events recorded since load or creation. */
    public List<DomainEvent> domainEvents() {
        return Collections.unmodifiableList(domainEvents);
    }

    /** Clear the recorded events; call after they have been published. */
    public void clearDomainEvents() {
        domainEvents.clear();
    }
}
