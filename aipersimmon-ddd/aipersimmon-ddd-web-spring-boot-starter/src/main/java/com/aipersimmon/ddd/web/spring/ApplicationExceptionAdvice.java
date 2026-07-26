package com.aipersimmon.ddd.web.spring;

import com.aipersimmon.ddd.application.ApplicationException;
import com.aipersimmon.ddd.application.ConcurrencyConflictException;
import com.aipersimmon.ddd.application.EntityNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps the optional {@code -application} exceptions to problem responses: {@link
 * EntityNotFoundException} to 404, {@link ConcurrencyConflictException} to 409, and the base {@link
 * ApplicationException} (a use-case orchestration failure that is not a domain-rule violation) to
 * 422. Each honours an {@link com.aipersimmon.ddd.core.error.ErrorCode} the exception carries,
 * resolving it through the registry. Registered only when {@code aipersimmon-ddd-application} is on
 * the classpath.
 */
@RestControllerAdvice
public class ApplicationExceptionAdvice {

  private final ProblemDetailFactory factory;

  public ApplicationExceptionAdvice(ProblemDetailFactory factory) {
    this.factory = factory;
  }

  @ExceptionHandler(EntityNotFoundException.class)
  public ProblemDetail handleNotFound(EntityNotFoundException ex) {
    return factory.fromCoded(ex.errorCode(), ex.getMessage(), HttpStatus.NOT_FOUND);
  }

  @ExceptionHandler(ConcurrencyConflictException.class)
  public ProblemDetail handleConflict(ConcurrencyConflictException ex) {
    return factory.fromCoded(ex.errorCode(), ex.getMessage(), HttpStatus.CONFLICT);
  }

  @ExceptionHandler(ApplicationException.class)
  public ProblemDetail handleApplication(ApplicationException ex) {
    return factory.fromCoded(ex.errorCode(), ex.getMessage(), HttpStatus.UNPROCESSABLE_ENTITY);
  }
}
