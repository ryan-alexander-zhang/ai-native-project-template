package com.aipersimmon.ddd.core.rule;

import com.aipersimmon.ddd.core.error.ErrorCode;

/**
 * A single business invariant expressed as a first-class object rather than an inline
 * {@code if (...) throw}. A rule knows how to test itself ({@link #isBroken()}) and how
 * to describe its own violation ({@link #message()}), so the same rule can be reused,
 * unit-tested in isolation, and named at the call site.
 *
 * <p>An aggregate enforces a rule through
 * {@link com.aipersimmon.ddd.core.model.AbstractAggregateRoot#checkRule(BusinessRule)},
 * which throws a {@link BusinessRuleViolationException} when the rule is broken.
 */
public interface BusinessRule {

    /** Whether this invariant is currently violated. */
    boolean isBroken();

    /** A human-readable description of the violation. */
    String message();

    /** The stable machine code for this violation, or {@code null} if none. */
    default ErrorCode errorCode() {
        return null;
    }
}
