package com.aipersimmon.ddd.application;

import com.aipersimmon.ddd.core.error.ErrorCode;
import java.util.Optional;

/**
 * Base type for exceptions raised while orchestrating a use case — failures that are not
 * domain-rule violations, such as a missing aggregate or a conflicting request. Extend it for
 * specific application errors so callers can distinguish them from domain-rule failures and
 * technical faults.
 *
 * <p>Like a domain exception it may carry a stable {@link ErrorCode}; the message-only constructors
 * remain for errors that do not need one.
 */
public class ApplicationException extends RuntimeException {

  private final transient ErrorCode errorCode;

  public ApplicationException(String message) {
    this(null, message, null);
  }

  public ApplicationException(String message, Throwable cause) {
    this(null, message, cause);
  }

  public ApplicationException(ErrorCode errorCode, String message) {
    this(errorCode, message, null);
  }

  public ApplicationException(ErrorCode errorCode, String message, Throwable cause) {
    super(message, cause);
    this.errorCode = errorCode;
  }

  /** The stable error code this exception carries, if any. */
  public Optional<ErrorCode> errorCode() {
    return Optional.ofNullable(errorCode);
  }
}
