package com.aipersimmon.ddd.application;

/**
 * Base type for exceptions raised while orchestrating a use case — failures that
 * are not domain-rule violations, such as a missing aggregate or a conflicting
 * request. Extend it for specific application errors so callers can distinguish
 * them from domain-rule failures and technical faults.
 */
public class ApplicationException extends RuntimeException {

    public ApplicationException(String message) {
        super(message);
    }

    public ApplicationException(String message, Throwable cause) {
        super(message, cause);
    }
}
