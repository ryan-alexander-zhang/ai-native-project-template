package com.aipersimmon.ddd.operationlog.cqrs.capture;

import com.aipersimmon.ddd.operationlog.engine.classifier.BeanValidationFailures;
import com.aipersimmon.ddd.operationlog.model.Completion;

/**
 * Default heuristic: a Bean Validation rejection is raised before the transaction begins, so it is
 * {@code NOT_STARTED}; anything else that reached the outer interceptor came from a
 * started-then-rolled-back transaction, so it is {@code ROLLED_BACK}. Consumers may override with a
 * richer policy.
 *
 * <p>Recognition is delegated to {@link BeanValidationFailures} so this policy and the default
 * classifier cannot disagree about what a validation failure is — and, more pointedly, so neither
 * matches on the simple name {@code ConstraintViolationException}, which Hibernate also uses for a
 * database constraint failing at flush. That one is a transaction that started and rolled back, so
 * reading it as a validation rejection records the opposite of what happened.
 */
public final class DefaultFailureCompletionPolicy implements FailureCompletionPolicy {

  @Override
  public Completion decide(Throwable failure) {
    return BeanValidationFailures.isBeanValidation(failure)
        ? Completion.NOT_STARTED
        : Completion.ROLLED_BACK;
  }
}
