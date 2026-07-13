package com.aipersimmon.ddd.archunit.fixture.bad.ordering.domain;

import com.aipersimmon.ddd.core.error.ErrorCode;
import com.aipersimmon.ddd.core.rule.Invariant;
import org.springframework.stereotype.Component;

/**
 * Violates {@code invariantsShouldNotBeSpringComponents}: an {@link Invariant}
 * declared as a Spring-managed bean rather than a plain domain object.
 */
@Component
public class BadSpringManagedRule implements Invariant {

    @Override
    public boolean isBroken() {
        return false;
    }

    @Override
    public String message() {
        return "never broken";
    }

    @Override
    public ErrorCode errorCode() {
        return () -> "fixture.never-broken";
    }
}
