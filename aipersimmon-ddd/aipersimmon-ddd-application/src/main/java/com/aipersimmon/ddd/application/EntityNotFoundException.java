package com.aipersimmon.ddd.application;

import com.aipersimmon.ddd.core.error.ErrorCode;

/**
 * Raised while orchestrating a use case when a referenced aggregate or resource does
 * not exist. It is an application-level failure (not a domain-rule violation), so it
 * extends {@link ApplicationException}; an interface layer maps it to "not found".
 */
public class EntityNotFoundException extends ApplicationException {

    public EntityNotFoundException(String message) {
        super(message);
    }

    public EntityNotFoundException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
