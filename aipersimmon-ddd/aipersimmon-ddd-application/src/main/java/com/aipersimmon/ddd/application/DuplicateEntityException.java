package com.aipersimmon.ddd.application;

import com.aipersimmon.ddd.core.error.ErrorCode;

/**
 * Raised while orchestrating a use case when a create loses a uniqueness race or targets an
 * identity or natural key that already exists. It is an application-level failure, so it extends
 * {@link ApplicationException}; an interface layer maps it to "conflict". Infrastructure typically
 * translates a framework duplicate-key exception into this type at the application boundary.
 *
 * <p>Unlike {@link ConcurrencyConflictException} this is a <em>stable</em> conflict and must not be
 * retried: rerunning the create deterministically conflicts again, whereas an optimistic-lock race
 * is transient and a retry is expected to succeed. Keeping the two as distinct types is what lets a
 * retry policy catch the transient one without ever replaying a duplicate create.
 */
public class DuplicateEntityException extends ApplicationException {

  public DuplicateEntityException(String message) {
    super(message);
  }

  public DuplicateEntityException(String message, Throwable cause) {
    super(message, cause);
  }

  public DuplicateEntityException(ErrorCode errorCode, String message, Throwable cause) {
    super(errorCode, message, cause);
  }
}
