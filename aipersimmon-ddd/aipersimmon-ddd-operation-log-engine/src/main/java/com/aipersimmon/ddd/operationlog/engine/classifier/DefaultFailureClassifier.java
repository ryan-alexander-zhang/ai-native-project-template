package com.aipersimmon.ddd.operationlog.engine.classifier;

import com.aipersimmon.ddd.application.ConcurrencyConflictException;
import com.aipersimmon.ddd.core.error.ErrorCategory;
import com.aipersimmon.ddd.core.error.ErrorCode;
import com.aipersimmon.ddd.core.exception.DomainException;
import com.aipersimmon.ddd.operationlog.model.OperationLogInvocation;
import com.aipersimmon.ddd.operationlog.spi.ClassifiedOutcome;
import com.aipersimmon.ddd.operationlog.spi.FailureClassifier;

/**
 * Default classification: a concurrency conflict is a retryable technical {@code FAILED} tagged
 * {@code CONCURRENCY}; an expected {@link DomainException} (business rule, authorization) and a
 * Bean Validation rejection are both {@code REJECTED} carrying a stable code/category; anything
 * else is an unexpected {@code FAILED}. It never stores the raw exception message — only stable
 * codes and generic safe summaries.
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
