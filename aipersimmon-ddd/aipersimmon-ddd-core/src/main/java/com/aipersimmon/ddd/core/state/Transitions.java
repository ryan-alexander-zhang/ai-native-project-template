package com.aipersimmon.ddd.core.state;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * A minimal, dependency-free guard over the legal state transitions of an
 * aggregate or entity. Declare the allowed transitions once — typically in a
 * {@code private static final} field — then call {@link #check} inside
 * intention-revealing methods such as {@code confirm()} or {@code cancel()}:
 *
 * <pre>{@code
 * private static final Transitions<Status> RULES = Transitions.<Status>of()
 *         .allow(Status.PENDING, Status.CONFIRMED)
 *         .allow(Status.PENDING, Status.CANCELLED);
 *
 * public void confirm() {
 *     RULES.check(status, Status.CONFIRMED);
 *     this.status = Status.CONFIRMED;
 * }
 * }</pre>
 *
 * <p>This is not a base class and not a state-machine engine: a domain object
 * uses it, it does not extend it. The ubiquitous-language methods stay on the
 * surface while the transition table lives in one place.
 *
 * @param <S> the state type, usually an enum
 */
public final class Transitions<S> {

    private final Map<S, Set<S>> allowed = new HashMap<>();

    private Transitions() {
    }

    /** Start declaring a transition table. */
    public static <S> Transitions<S> of() {
        return new Transitions<>();
    }

    /**
     * Declare {@code from -> to} as a legal transition.
     *
     * @return this table, for chaining
     */
    public Transitions<S> allow(S from, S to) {
        allowed.computeIfAbsent(from, key -> new HashSet<>()).add(to);
        return this;
    }

    /** Whether {@code from -> to} was declared legal. */
    public boolean permits(S from, S to) {
        return allowed.getOrDefault(from, Set.of()).contains(to);
    }

    /**
     * Assert that {@code from -> to} is legal.
     *
     * @throws IllegalStateTransitionException if the transition was not declared
     */
    public void check(S from, S to) {
        if (!permits(from, to)) {
            throw new IllegalStateTransitionException(from, to);
        }
    }
}
