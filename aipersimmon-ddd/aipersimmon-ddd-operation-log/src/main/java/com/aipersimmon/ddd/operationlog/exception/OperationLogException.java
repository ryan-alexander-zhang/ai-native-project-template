package com.aipersimmon.ddd.operationlog.exception;

/**
 * Base unchecked exception for Operation Log component faults — invalid template, definition
 * conflict, missing resolver, or a size-budget violation. It is a component/config fault, not a
 * domain exception, so it does not extend the domain error base.
 */
public class OperationLogException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** With a message. */
  public OperationLogException(String message) {
    super(message);
  }

  /** With a message and cause. */
  public OperationLogException(String message, Throwable cause) {
    super(message, cause);
  }
}
