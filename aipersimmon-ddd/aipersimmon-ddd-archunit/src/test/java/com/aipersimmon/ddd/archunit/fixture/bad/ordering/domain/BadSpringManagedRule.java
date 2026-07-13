package com.aipersimmon.ddd.archunit.fixture.bad.ordering.domain;

import com.aipersimmon.ddd.core.rule.BusinessRule;
import org.springframework.stereotype.Component;

/**
 * Violates {@code businessRulesShouldNotBeSpringComponents}: a {@link BusinessRule}
 * declared as a Spring-managed bean rather than a plain domain object.
 */
@Component
public class BadSpringManagedRule implements BusinessRule {

    @Override
    public boolean isBroken() {
        return false;
    }

    @Override
    public String message() {
        return "never broken";
    }
}
