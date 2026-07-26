package com.aipersimmon.ddd.operationlog.cqrs.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.aipersimmon.ddd.operationlog.model.Completion;
import org.junit.jupiter.api.Test;

class DefaultFailureCompletionPolicyTest {

  private final DefaultFailureCompletionPolicy policy = new DefaultFailureCompletionPolicy();

  /** A stand-in whose simple name matches the Bean Validation exception the policy keys on. */
  static final class ConstraintViolationException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    ConstraintViolationException() {
      super("invalid");
    }
  }

  @Test
  void validation_failure_is_not_started() {
    assertEquals(Completion.NOT_STARTED, policy.decide(new ConstraintViolationException()));
  }

  @Test
  void validation_failure_in_cause_chain_is_not_started() {
    Throwable wrapped = new IllegalStateException("wrap", new ConstraintViolationException());
    assertEquals(Completion.NOT_STARTED, policy.decide(wrapped));
  }

  @Test
  void other_failures_are_rolled_back() {
    assertEquals(Completion.ROLLED_BACK, policy.decide(new IllegalStateException("boom")));
  }
}
