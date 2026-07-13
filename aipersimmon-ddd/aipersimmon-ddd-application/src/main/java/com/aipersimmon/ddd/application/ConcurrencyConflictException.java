package com.aipersimmon.ddd.application;

import com.aipersimmon.ddd.core.error.ErrorCode;

/**
 * Raised while orchestrating a use case when a write loses an optimistic-locking race
 * — the aggregate was modified concurrently. It is an application-level failure, so it
 * extends {@link ApplicationException}; an interface layer maps it to "conflict".
 * Infrastructure typically translates a framework optimistic-lock exception into this
 * type at the application boundary.
 */
public class ConcurrencyConflictException extends ApplicationException {

    public ConcurrencyConflictException(String message) {
        super(message);
    }

    public ConcurrencyConflictException(String message, Throwable cause) {
        super(message, cause);
    }

    public ConcurrencyConflictException(ErrorCode errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }
}
