package com.aipersimmon.ddd.core.rule;

import com.aipersimmon.ddd.core.error.ErrorCode;

/**
 * A single business invariant expressed as a first-class object rather than an inline
 * {@code if (...) throw}. An invariant knows how to test itself ({@link #isBroken()}),
 * how to describe its own violation ({@link #message()}), and which stable
 * {@link ErrorCode} identifies it — so the same invariant can be reused, unit-tested in
 * isolation, and named at the call site.
 *
 * <p>An aggregate enforces an invariant through
 * {@link com.aipersimmon.ddd.core.model.AbstractAggregateRoot#checkInvariant(Invariant)},
 * which throws an {@link InvariantViolationException} when the invariant is broken.
 *
 * <p>Naming: this is the assertion-style sibling of the decision-style
 * {@code Policy}/{@code Specification} seen in some reference projects (see
 * design-00003 §4.6). It is deliberately <em>not</em> named {@code Validator}: that word
 * belongs to edge input validation (Bean Validation on request DTOs), a separate,
 * non-exceptional concern (see the guard-vs-validate split in §八).
 */
public interface Invariant {

    /** Whether this invariant is currently violated. */
    boolean isBroken();

    /** A human-readable description of the violation. */
    String message();

    /**
     * The stable, machine-readable code that identifies this invariant. Required: an
     * invariant worth expressing as a first-class object deserves a stable identity that
     * travels unchanged to the edge (design-00003 §4.5). Trivial one-off guards that need
     * no such identity should stay inline {@code coded throw} rather than become an
     * {@code Invariant}.
     */
    ErrorCode errorCode();
}
