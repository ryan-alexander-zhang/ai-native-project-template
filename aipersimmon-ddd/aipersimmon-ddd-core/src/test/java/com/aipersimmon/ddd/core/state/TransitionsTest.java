package com.aipersimmon.ddd.core.state;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.core.error.ErrorCategory;
import com.aipersimmon.ddd.core.error.ErrorCode;
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

  // --- refusal codes (issue-00138): the table names the refusal, once, where the transition is
  // declared — so a refused mechanical move reaches the edge with a stable identity instead of a
  // bare message, and the aggregate never writes the same guard twice to get one.

  enum Code implements ErrorCode {
    NOT_PENDING("test.not-pending"),
    NOT_CONFIRMED("test.not-confirmed");

    private final String code;

    Code(String code) {
      this.code = code;
    }

    @Override
    public String code() {
      return code;
    }

    @Override
    public ErrorCategory category() {
      return ErrorCategory.CONFLICT;
    }
  }

  private static final Transitions<Status> CODED =
      Transitions.<Status>of()
          .allow(Status.PENDING, Status.CONFIRMED, Code.NOT_PENDING)
          .allow(Status.CONFIRMED, Status.CANCELLED, Code.NOT_CONFIRMED);

  @Test
  void aRefusedTransitionCarriesTheCodeDeclaredForItsDestination() {
    IllegalStateTransitionException refused =
        assertThrows(
            IllegalStateTransitionException.class,
            () -> CODED.check(Status.CANCELLED, Status.CONFIRMED));
    assertEquals(Code.NOT_PENDING, refused.errorCode().orElseThrow());
  }

  @Test
  void aRefusedTransitionToAnUndeclaredDestinationCarriesNoCode() {
    IllegalStateTransitionException refused =
        assertThrows(
            IllegalStateTransitionException.class,
            () -> CODED.check(Status.CANCELLED, Status.PENDING));
    assertTrue(refused.errorCode().isEmpty());
  }

  @Test
  void anUncodedTableStillRefusesWithoutACode() {
    IllegalStateTransitionException refused =
        assertThrows(
            IllegalStateTransitionException.class,
            () -> RULES.check(Status.CONFIRMED, Status.PENDING));
    assertTrue(refused.errorCode().isEmpty());
  }

  /**
   * Two edges into one destination must agree on the refusal's name, and disagreement is a
   * declaration-time error — at class initialisation, where the author is looking — not a different
   * exception depending on which illegal attempt happened to run first.
   */
  @Test
  void conflictingCodesForOneDestinationAreRefusedAtDeclaration() {
    Transitions<Status> table =
        Transitions.<Status>of().allow(Status.PENDING, Status.CANCELLED, Code.NOT_PENDING);
    assertThrows(
        IllegalArgumentException.class,
        () -> table.allow(Status.CONFIRMED, Status.CANCELLED, Code.NOT_CONFIRMED));
    // Restating the same code is not a conflict.
    assertDoesNotThrow(() -> table.allow(Status.CONFIRMED, Status.CANCELLED, Code.NOT_PENDING));
  }
}
