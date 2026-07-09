package com.aipersimmon.ddd.core.state;

import com.aipersimmon.ddd.core.exception.DomainException;

/**
 * Raised when an aggregate or entity is asked to make a state transition that was
 * not declared legal in its {@link Transitions} table. It is a domain-rule
 * violation, so it extends {@link DomainException}.
 */
public final class IllegalStateTransitionException extends DomainException {

    public IllegalStateTransitionException(Object from, Object to) {
        super("illegal state transition: " + from + " -> " + to);
    }
}
