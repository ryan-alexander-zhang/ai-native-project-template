package com.aipersimmon.ddd.operationlog.engine.classifier;

import com.aipersimmon.ddd.application.ApplicationException;
import com.aipersimmon.ddd.application.ConcurrencyConflictException;
import com.aipersimmon.ddd.core.error.ErrorCategory;
import com.aipersimmon.ddd.core.error.ErrorCode;
import com.aipersimmon.ddd.core.exception.DomainException;
import com.aipersimmon.ddd.operationlog.model.OperationLogInvocation;
import com.aipersimmon.ddd.operationlog.spi.ClassifiedOutcome;
import com.aipersimmon.ddd.operationlog.spi.FailureClassifier;

/**
 * Default classification: a concurrency conflict is a retryable technical {@code FAILED} tagged
 * {@code CONCURRENCY}; an expected {@link DomainException} (business rule, authorization), an
 * {@link ApplicationException} (a missing aggregate, a conflicting request) and a Bean Validation
 * rejection are all {@code REJECTED} carrying a stable code/category; anything else is an
 * unexpected {@code FAILED}. It never stores the raw exception message — only stable codes and
 * generic safe summaries.
 */
public final class DefaultFailureClassifier implements FailureClassifier {

  @Override
  public ClassifiedOutcome classify(Throwable failure, OperationLogInvocation invocation) {
    if (failure instanceof ConcurrencyConflictException) {
      return ClassifiedOutcome.failed(
          "concurrency.conflict", "CONCURRENCY", "concurrent modification");
    }
    if (failure instanceof DomainException domain) {
      String code = domain.errorCode().map(ErrorCode::code).orElse("domain.rejected");
      String category =
          domain.errorCode().map(c -> c.category().name()).orElse(ErrorCategory.DOMAIN_RULE.name());
      return ClassifiedOutcome.rejected(code, category, "business rule rejected");
    }
    // An application-level refusal is a rejection for the same reason a domain one is: the client
    // is
    // wrong and the service is not. This branch MUST stay below the ConcurrencyConflictException
    // one
    // above — that type extends ApplicationException, and losing an optimistic-lock race really is
    // a
    // transient technical FAILED, so the narrower check has to win.
    //
    // Without this branch every EntityNotFoundException (a 404) and DuplicateEntityException (a
    // 409)
    // landed in the unexpected bucket, which cost two things: the row was mislabelled, and the code
    // the exception was carrying was replaced by the constant "unexpected" — breaking the join
    // between
    // an audit row and the problem document the client saw. It also inflated the FAILED counter
    // with
    // every bad request, which is what makes an alert on FAILED unusable.
    //
    // An ApplicationException with no ErrorCode keeps the UNEXPECTED category rather than borrowing
    // DOMAIN_RULE: the outcome is still a rejection, but there is no honest category to claim, and
    // such an exception is one that ought to be given a code rather than quietly classified.
    if (failure instanceof ApplicationException application) {
      String code = application.errorCode().map(ErrorCode::code).orElse("application.rejected");
      String category =
          application
              .errorCode()
              .map(c -> c.category().name())
              .orElse(ErrorCategory.UNEXPECTED.name());
      return ClassifiedOutcome.rejected(code, category, "request rejected");
    }
    // Malformed input is a rejection, which is what Outcome.REJECTED says it covers ("a business
    // rule, validation, or authorization decision"). Left in the unexpected bucket it would both
    // mislabel the row and inflate the FAILED counter with every bad request — the interceptor that
    // raises it sits at order 100, so this is the third of the three ordinary paths, not an edge.
    if (BeanValidationFailures.isBeanValidation(failure)) {
      return ClassifiedOutcome.rejected(
          "validation.rejected", ErrorCategory.VALIDATION.name(), "input rejected by validation");
    }
    return ClassifiedOutcome.failed(
        "unexpected", ErrorCategory.UNEXPECTED.name(), "unexpected error");
  }
}
