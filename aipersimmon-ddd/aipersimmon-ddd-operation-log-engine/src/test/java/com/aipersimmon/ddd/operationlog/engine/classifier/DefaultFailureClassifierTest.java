package com.aipersimmon.ddd.operationlog.engine.classifier;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.aipersimmon.ddd.application.ApplicationException;
import com.aipersimmon.ddd.application.ConcurrencyConflictException;
import com.aipersimmon.ddd.application.EntityNotFoundException;
import com.aipersimmon.ddd.core.error.ErrorCategory;
import com.aipersimmon.ddd.core.error.ErrorCode;
import com.aipersimmon.ddd.core.exception.DomainException;
import com.aipersimmon.ddd.operationlog.model.Outcome;
import com.aipersimmon.ddd.operationlog.spi.ClassifiedOutcome;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DefaultFailureClassifierTest {

  private final DefaultFailureClassifier classifier = new DefaultFailureClassifier();

  @Test
  void concurrency_conflict_is_failed_concurrency() {
    ClassifiedOutcome out = classifier.classify(new ConcurrencyConflictException("clash"), null);
    assertEquals(Outcome.FAILED, out.outcome());
    assertEquals("CONCURRENCY", out.failure().category());
    assertEquals("concurrency.conflict", out.failure().code());
  }

  @Test
  void domain_exception_with_code_is_rejected_with_that_code() {
    ErrorCode code =
        new ErrorCode() {
          @Override
          public String code() {
            return "ordering.credit-exceeded";
          }

          @Override
          public ErrorCategory category() {
            return ErrorCategory.VALIDATION;
          }
        };
    ClassifiedOutcome out = classifier.classify(new DomainException(code, "msg"), null);
    assertEquals(Outcome.REJECTED, out.outcome());
    assertEquals("ordering.credit-exceeded", out.failure().code());
    assertEquals("VALIDATION", out.failure().category());
  }

  @Test
  void domain_exception_without_code_defaults_to_domain_rule() {
    ClassifiedOutcome out = classifier.classify(new DomainException("plain"), null);
    assertEquals(Outcome.REJECTED, out.outcome());
    assertEquals("domain.rejected", out.failure().code());
    assertEquals("DOMAIN_RULE", out.failure().category());
  }

  /**
   * An application-level refusal carries its own code into the row.
   *
   * <p>This is the branch that was missing. {@code ApplicationException} is the library's own base
   * type for "a missing aggregate or a conflicting request", it carries an {@link ErrorCode}
   * exactly as {@link DomainException} does, and every one of them used to land on {@code FAILED} /
   * {@code unexpected} — so a 404 was indistinguishable from a broken database, and the code the
   * exception was carrying never reached the audit row.
   */
  @Test
  void anapplicationExceptionWithACodeIsRejectedWithThatCode() {
    ClassifiedOutcome out =
        classifier.classify(
            new EntityNotFoundException(
                codeOf("customer.not-found", ErrorCategory.NOT_FOUND), "no"),
            null);
    assertEquals(Outcome.REJECTED, out.outcome());
    assertEquals("customer.not-found", out.failure().code());
    assertEquals("NOT_FOUND", out.failure().category());
  }

  /**
   * And the ordering that the new branch depends on: {@link ConcurrencyConflictException} extends
   * {@link ApplicationException}, so a branch placed above it would have swallowed the one case in
   * this family that genuinely is a transient technical fault. Losing an optimistic-lock race is
   * not the client being wrong, and it is the one of the three subclasses that should be retried.
   */
  @Test
  void aconcurrencyConflictIsNotSwallowedByTheApplicationBranch() {
    ClassifiedOutcome out =
        classifier.classify(
            new ConcurrencyConflictException(
                codeOf("ordering.stale-write", ErrorCategory.CONFLICT), "clash", null),
            null);
    assertEquals(Outcome.FAILED, out.outcome());
    assertEquals("concurrency.conflict", out.failure().code());
    assertEquals("CONCURRENCY", out.failure().category());
  }

  /**
   * A codeless {@code ApplicationException} has a defined outcome and does not throw: the row is a
   * rejection, but the category stays {@code UNEXPECTED} rather than borrowing {@code DOMAIN_RULE}
   * — there is no honest category to claim, and an application exception with no code is one that
   * ought to be given a code rather than quietly classified.
   */
  @Test
  void anapplicationExceptionWithoutACodeIsRejectedWithoutBorrowingACategory() {
    ClassifiedOutcome out = classifier.classify(new ApplicationException("plain"), null);
    assertEquals(Outcome.REJECTED, out.outcome());
    assertEquals("application.rejected", out.failure().code());
    assertEquals("UNEXPECTED", out.failure().category());
  }

  private static ErrorCode codeOf(String code, ErrorCategory category) {
    return new ErrorCode() {
      @Override
      public String code() {
        return code;
      }

      @Override
      public ErrorCategory category() {
        return category;
      }
    };
  }

  /**
   * A rejection, not an unexpected fault: {@code Outcome.REJECTED} is defined as "rejected by a
   * business rule, validation, or authorization decision". Left in the unexpected bucket, malformed
   * input both mislabels its row and inflates the FAILED counter with every bad request — and the
   * validation interceptor that raises it sits on the ordinary path, at order 100.
   */
  @Test
  void aBeanValidationRejectionIsRejectedNotUnexpected() {
    ClassifiedOutcome out =
        classifier.classify(
            new jakarta.validation.ConstraintViolationException("bad", Set.of()), null);
    assertEquals(Outcome.REJECTED, out.outcome());
    assertEquals("validation.rejected", out.failure().code());
    assertEquals("VALIDATION", out.failure().category());
  }

  /**
   * The same simple name as Bean Validation's exception, a different package, and the opposite
   * meaning: a database constraint failing at flush is a technical fault, so it must stay in the
   * unexpected bucket. Guards the classifier against the name-based match that misread it.
   */
  @Test
  void aDatabaseConstraintViolationStaysUnexpectedDespiteSharingTheName() {
    ClassifiedOutcome out = classifier.classify(new ConstraintViolationException(), null);
    assertEquals(Outcome.FAILED, out.outcome());
    assertEquals("unexpected", out.failure().code());
  }

  /** Stands in for {@code org.hibernate.exception.ConstraintViolationException}. */
  static final class ConstraintViolationException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    ConstraintViolationException() {
      super("duplicate key");
    }
  }

  @Test
  void unknown_throwable_is_unexpected_failed() {
    ClassifiedOutcome out = classifier.classify(new IllegalStateException("boom"), null);
    assertEquals(Outcome.FAILED, out.outcome());
    assertEquals("unexpected", out.failure().code());
    assertEquals("UNEXPECTED", out.failure().category());
  }
}
