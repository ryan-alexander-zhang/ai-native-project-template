package com.aipersimmon.ddd.operationlog.engine.classifier;

/**
 * Recognises a Bean Validation rejection, for the two default policies that have to agree about
 * one: it decides both the outcome (a rejection, not an unexpected fault) and the completion
 * (raised before the transaction begins, so nothing was started).
 *
 * <p>Matched by package rather than by simple name, and this is the whole reason the check lives in
 * one place. {@code jakarta.validation.ConstraintViolationException} and {@code
 * org.hibernate.exception.ConstraintViolationException} share a simple name but mean opposite
 * things: the first is input refused before any work began, the second is a database constraint
 * failing at flush — a transaction that started and rolled back. A simple-name match reads the
 * second as the first and records a rolled-back write as {@code NOT_STARTED}.
 *
 * <p>String matching rather than a type reference so the engine keeps no dependency on the Bean
 * Validation API, which a consumer may not have.
 */
public final class BeanValidationFailures {

  private static final String PACKAGE_PREFIX = "jakarta.validation.";

  private BeanValidationFailures() {}

  /**
   * Whether {@code failure} or anything in its cause chain came from Bean Validation.
   *
   * <p>The chain is walked because the rejection usually arrives wrapped — the validation
   * interceptor's exception inside whatever the dispatch layer added around it.
   */
  public static boolean isBeanValidation(Throwable failure) {
    for (Throwable current = failure; current != null; current = current.getCause()) {
      if (current.getClass().getName().startsWith(PACKAGE_PREFIX)) {
        return true;
      }
    }
    return false;
  }
}
