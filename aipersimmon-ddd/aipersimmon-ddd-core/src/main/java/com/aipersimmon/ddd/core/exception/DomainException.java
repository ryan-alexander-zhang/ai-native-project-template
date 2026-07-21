package com.aipersimmon.ddd.core.exception;

import com.aipersimmon.ddd.core.error.ErrorCode;
import java.util.Optional;

/**
 * Base type for exceptions that signal a violation of a domain rule or invariant. Extend it for
 * specific domain errors so callers can distinguish business-rule failures from technical faults.
 *
 * <p>It may carry a stable {@link ErrorCode} so the error's machine-readable identity is fixed at
 * the point it is thrown and can travel unchanged to the edge. The code is optional: the
 * message-only constructors remain for errors that do not need one.
 */
public class DomainException extends RuntimeException {

  private final transient ErrorCode errorCode;

  public DomainException(String message) {
    this(null, message, null);
  }

  public DomainException(String message, Throwable cause) {
    this(null, message, cause);
  }

  public DomainException(ErrorCode errorCode, String message) {
    this(errorCode, message, null);
  }

  public DomainException(ErrorCode errorCode, String message, Throwable cause) {
    super(message, cause);
    this.errorCode = errorCode;
  }

  /** The stable error code this exception carries, if any. */
  public Optional<ErrorCode> errorCode() {
    return Optional.ofNullable(errorCode);
  }
}
