package com.aipersimmon.ddd.archunit.fixture.bad.ordering.domain;

import com.aipersimmon.ddd.core.rule.BusinessRule;
import com.aipersimmon.ddd.core.rule.BusinessRuleViolationException;

/**
 * Violates {@code businessRuleViolationsShouldOnlyComeFromCheckRule}: it raises a
 * {@link BusinessRuleViolationException} by constructing it directly, instead of going
 * through {@code AbstractAggregateRoot.checkRule}.
 */
public class BadDirectRuleViolation implements BusinessRule {

    @Override
    public boolean isBroken() {
        return true;
    }

    @Override
    public String message() {
        return "always broken";
    }

    public void enforce() {
        throw new BusinessRuleViolationException(this);
    }
}
