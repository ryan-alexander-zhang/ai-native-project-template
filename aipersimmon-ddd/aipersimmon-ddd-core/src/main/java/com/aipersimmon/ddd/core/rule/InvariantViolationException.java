package com.aipersimmon.ddd.core.rule;

import com.aipersimmon.ddd.core.exception.DomainException;

/**
 * Thrown when an {@link Invariant} is broken. It is a domain-rule violation, so it
 * extends {@link DomainException} and carries the invariant's {@link Invariant#errorCode()}
 * and {@link Invariant#message()}.
 */
public final class InvariantViolationException extends DomainException {

    public InvariantViolationException(Invariant invariant) {
        super(invariant.errorCode(), invariant.message());
    }
}
