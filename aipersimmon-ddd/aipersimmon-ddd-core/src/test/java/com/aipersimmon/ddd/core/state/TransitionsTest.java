package com.aipersimmon.ddd.core.state;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TransitionsTest {

  enum Status {
    PENDING,
    CONFIRMED,
    CANCELLED
  }

  private static final Transitions<Status> RULES =
      Transitions.<Status>of()
          .allow(Status.PENDING, Status.CONFIRMED)
          .allow(Status.PENDING, Status.CANCELLED);

  @Test
  void permitsDeclaredTransitions() {
    assertTrue(RULES.permits(Status.PENDING, Status.CONFIRMED));
    assertTrue(RULES.permits(Status.PENDING, Status.CANCELLED));
  }

  @Test
  void rejectsUndeclaredTransitions() {
    assertFalse(RULES.permits(Status.CONFIRMED, Status.PENDING));
    assertFalse(RULES.permits(Status.CONFIRMED, Status.CANCELLED));
  }

  @Test
  void checkPassesForLegalTransition() {
    assertDoesNotThrow(() -> RULES.check(Status.PENDING, Status.CONFIRMED));
  }

  @Test
  void checkThrowsForIllegalTransition() {
    assertThrows(
        IllegalStateTransitionException.class, () -> RULES.check(Status.CONFIRMED, Status.PENDING));
  }
}
