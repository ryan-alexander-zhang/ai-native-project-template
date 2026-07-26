package com.aipersimmon.ddd.core.rule;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Composition is the whole reason this exists rather than a bare {@code Predicate}: a combined rule
 * must still read as a named domain concept, and must evaluate as the parts say it should.
 */
class SpecificationTest {

  private static final Specification<String> NON_BLANK = s -> !s.isBlank();
  private static final Specification<String> SHORT = s -> s.length() <= 3;

  @Test
  void andIsSatisfiedOnlyWhenBothAre() {
    Specification<String> both = NON_BLANK.and(SHORT);

    assertTrue(both.isSatisfiedBy("ab"));
    assertFalse(both.isSatisfiedBy("   "), "blank fails the first");
    assertFalse(both.isSatisfiedBy("abcd"), "too long fails the second");
  }

  @Test
  void orIsSatisfiedWhenEitherIs() {
    Specification<String> either = SHORT.or(NON_BLANK);

    assertTrue(either.isSatisfiedBy("  "), "blank but short");
    assertTrue(either.isSatisfiedBy("abcd"), "long but non-blank");
    assertFalse(either.isSatisfiedBy("    "), "neither: four blanks are long and blank");
  }

  @Test
  void notInverts() {
    assertTrue(SHORT.not().isSatisfiedBy("abcd"));
    assertFalse(SHORT.not().isSatisfiedBy("abc"));
  }

  @Test
  void compositionNestsWithoutParenthesesSurprises() {
    // inGoodStanding AND (verified OR employee) — the shape the Javadoc advertises.
    Specification<String> composed = NON_BLANK.and(SHORT.or(s -> s.startsWith("emp")));

    assertTrue(composed.isSatisfiedBy("abc"), "non-blank and short");
    assertTrue(composed.isSatisfiedBy("employee"), "non-blank and, though long, an employee");
    assertFalse(composed.isSatisfiedBy("abcdef"), "non-blank but neither short nor an employee");
  }
}
