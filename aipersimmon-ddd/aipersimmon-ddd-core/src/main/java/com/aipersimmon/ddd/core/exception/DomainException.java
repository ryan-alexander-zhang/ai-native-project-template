package com.aipersimmon.ddd.core.exception;

/**
 * Base type for exceptions that signal a violation of a domain rule or invariant.
 * Extend it for specific domain errors so callers can distinguish business-rule
 * failures from technical faults.
 */
public class DomainException extends RuntimeException {

    public DomainException(String message) {
        super(message);
    }

    public DomainException(String message, Throwable cause) {
        super(message, cause);
    }
}
