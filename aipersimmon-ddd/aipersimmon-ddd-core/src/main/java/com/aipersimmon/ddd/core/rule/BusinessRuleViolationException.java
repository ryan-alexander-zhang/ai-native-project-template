package com.aipersimmon.ddd.core.rule;

import com.aipersimmon.ddd.core.exception.DomainException;

/**
 * Thrown when a {@link BusinessRule} is broken. It is a domain-rule violation, so it
 * extends {@link DomainException} and carries the rule's {@link BusinessRule#errorCode()}
 * and {@link BusinessRule#message()}.
 */
public final class BusinessRuleViolationException extends DomainException {

    public BusinessRuleViolationException(BusinessRule rule) {
        super(rule.errorCode(), rule.message());
    }
}
