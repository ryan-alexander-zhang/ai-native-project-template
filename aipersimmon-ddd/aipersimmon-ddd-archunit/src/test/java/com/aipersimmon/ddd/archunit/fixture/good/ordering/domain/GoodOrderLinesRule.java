package com.aipersimmon.ddd.archunit.fixture.good.ordering.domain;

import com.aipersimmon.ddd.core.rule.BusinessRule;

/**
 * A well-formed {@link BusinessRule}: a domain invariant, framework-free, plain (not a
 * Spring bean), and raised only through {@code AbstractAggregateRoot.checkRule} — never
 * by constructing the violation directly.
 */
public record GoodOrderLinesRule(int lineCount) implements BusinessRule {

    @Override
    public boolean isBroken() {
        return lineCount <= 0;
    }

    @Override
    public String message() {
        return "an order needs at least one line";
    }
}
