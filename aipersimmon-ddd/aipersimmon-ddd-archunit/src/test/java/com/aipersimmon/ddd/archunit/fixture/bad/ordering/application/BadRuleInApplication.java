package com.aipersimmon.ddd.archunit.fixture.bad.ordering.application;

import com.aipersimmon.ddd.core.rule.BusinessRule;

/**
 * Violates {@code businessRulesShouldResideInDomain}: a {@link BusinessRule} declared in
 * the application layer instead of the domain.
 */
public record BadRuleInApplication(int value) implements BusinessRule {

    @Override
    public boolean isBroken() {
        return value < 0;
    }

    @Override
    public String message() {
        return "value must be >= 0";
    }
}
