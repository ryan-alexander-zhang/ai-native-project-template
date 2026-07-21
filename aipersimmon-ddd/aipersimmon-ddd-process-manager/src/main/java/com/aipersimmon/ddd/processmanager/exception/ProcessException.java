package com.aipersimmon.ddd.processmanager.exception;

/**
 * Base of the process-runtime exceptions, so callers can catch the family in one place. These are
 * runtime-coordination failures (identity, lifecycle, concurrency, serialization), distinct from a
 * bounded context's domain exceptions.
 */
public abstract class ProcessException extends RuntimeException {

  protected ProcessException(String message) {
    super(message);
  }

  protected ProcessException(String message, Throwable cause) {
    super(message, cause);
  }
}
