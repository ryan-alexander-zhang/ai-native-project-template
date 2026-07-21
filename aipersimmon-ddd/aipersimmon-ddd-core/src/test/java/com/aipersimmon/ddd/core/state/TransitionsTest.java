package com.aipersimmon.ddd.core.state;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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

  @Test
  void ofReturnsAUsableTableAndAllowChainsAndRecords() {
    Transitions<Status> table = Transitions.of();
    assertNotNull(table);

    // allow() returns the same table (fluent chaining) ...
    assertSame(table, table.allow(Status.PENDING, Status.CONFIRMED));
    // ... and actually records the transition into a real mutable set.
    assertTrue(table.permits(Status.PENDING, Status.CONFIRMED));
    assertFalse(table.permits(Status.PENDING, Status.CANCELLED));
  }
}
