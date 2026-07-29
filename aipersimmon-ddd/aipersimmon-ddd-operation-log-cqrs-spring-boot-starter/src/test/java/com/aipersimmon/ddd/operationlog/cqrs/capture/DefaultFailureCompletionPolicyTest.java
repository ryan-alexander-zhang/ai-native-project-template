package com.aipersimmon.ddd.operationlog.cqrs.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.aipersimmon.ddd.operationlog.model.Completion;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DefaultFailureCompletionPolicyTest {

  private final DefaultFailureCompletionPolicy policy = new DefaultFailureCompletionPolicy();

  /**
   * Stands in for {@code org.hibernate.exception.ConstraintViolationException}: a database
   * constraint failing at flush, which happens <em>after</em> the transaction started. Declared
   * with that exact simple name and a different package, because that is the whole collision — no
   * Hibernate dependency is needed to show it.
   */
  static final class ConstraintViolationException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    ConstraintViolationException() {
      super("duplicate key");
    }
  }

  private static jakarta.validation.ConstraintViolationException beanValidationFailure() {
    return new jakarta.validation.ConstraintViolationException("invalid", Set.of());
  }

  @Test
  void aBeanValidationRejectionStartedNothing() {
    assertEquals(Completion.NOT_STARTED, policy.decide(beanValidationFailure()));
  }

  @Test
  void aBeanValidationRejectionIsFoundThroughTheCauseChain() {
    Throwable wrapped = new IllegalStateException("wrap", beanValidationFailure());
    assertEquals(Completion.NOT_STARTED, policy.decide(wrapped));
  }

  /**
   * The regression this policy exists to avoid: a database constraint violation arrives after the
   * transaction has started and been rolled back. Matching on the simple name reported it as {@code
   * NOT_STARTED} — the opposite of what happened — because Hibernate's type is named the same as
   * Bean Validation's.
   */
  @Test
  void aDatabaseConstraintViolationRolledBackDespiteSharingTheName() {
    assertEquals(
        "ConstraintViolationException",
        ConstraintViolationException.class.getSimpleName(),
        "this test is only meaningful while the impostor shares the simple name");
    assertEquals(Completion.ROLLED_BACK, policy.decide(new ConstraintViolationException()));
  }

  @Test
  void otherFailuresRolledBack() {
    assertEquals(Completion.ROLLED_BACK, policy.decide(new IllegalStateException("boom")));
  }
}
