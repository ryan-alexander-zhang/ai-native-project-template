package com.aipersimmon.ddd.operationlog.cqrs.capture;

import com.aipersimmon.ddd.operationlog.model.Completion;

/**
 * Decides the transaction {@link Completion} for a failure surfaced on the outer path — {@code
 * NOT_STARTED} when the command was rejected before its transaction began (e.g. validation), {@code
 * ROLLED_BACK} when a started transaction rolled back. Kept as a policy because this distinction is
 * a documented, refinable heuristic (design-00008 §6.1 / decision-00017 D5).
 */
@FunctionalInterface
public interface FailureCompletionPolicy {

  /** The completion state to record for {@code failure}. */
  Completion decide(Throwable failure);
}
