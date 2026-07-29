package com.aipersimmon.ddd.cqrs.spring;

/**
 * Thrown during startup when no {@code PlatformTransactionManager} is available and {@code
 * aipersimmon.ddd.cqrs.transaction.required} is left on.
 *
 * <p>A dedicated type so a {@code FailureAnalyzer} can turn it into a readable startup report, and
 * so a test can name the condition it is asserting.
 */
public class MissingTransactionManagerException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public MissingTransactionManagerException(String message) {
    super(message);
  }
}
