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
 * {@code CONCURRENCY}; an expected {@link DomainException} (validation, authorization, business
 * rule) is a {@code REJECTED} carrying its stable code/category; anything else is an unexpected
 * {@code FAILED}. It never stores the raw exception message — only stable codes and generic safe
 * summaries.
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
    return ClassifiedOutcome.failed(
        "unexpected", ErrorCategory.UNEXPECTED.name(), "unexpected error");
  }
}
