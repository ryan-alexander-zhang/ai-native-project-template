package com.aipersimmon.ddd.operationlog.cqrs.capture;

import com.aipersimmon.ddd.operationlog.model.Completion;

/**
 * Default heuristic: a Bean Validation failure (a {@code ConstraintViolationException}, matched by
 * simple name to avoid a hard dependency) is raised before the transaction begins, so it is {@code
 * NOT_STARTED}; anything else that reached the outer interceptor came from a
 * started-then-rolled-back transaction, so it is {@code ROLLED_BACK}. Consumers may override with a
 * richer policy.
 */
public final class DefaultFailureCompletionPolicy implements FailureCompletionPolicy {

  @Override
  public Completion decide(Throwable failure) {
    for (Throwable t = failure; t != null; t = t.getCause()) {
      if ("ConstraintViolationException".equals(t.getClass().getSimpleName())) {
        return Completion.NOT_STARTED;
      }
    }
    return Completion.ROLLED_BACK;
  }
}
