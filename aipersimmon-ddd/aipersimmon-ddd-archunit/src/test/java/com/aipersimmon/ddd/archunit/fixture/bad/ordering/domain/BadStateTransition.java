package com.aipersimmon.ddd.archunit.fixture.bad.ordering.domain;

import com.aipersimmon.ddd.core.state.IllegalStateTransitionException;

/**
 * Violates {@code illegalStateTransitionsShouldOnlyComeFromTransitions}: it raises an
 * {@link IllegalStateTransitionException} by constructing it directly, instead of
 * declaring the legal transitions in a {@code Transitions} table and routing the check
 * through it.
 */
public class BadStateTransition {

    public void ship() {
        throw new IllegalStateTransitionException("PENDING", "SHIPPED");
    }
}
