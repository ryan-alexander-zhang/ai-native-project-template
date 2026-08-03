package com.example.ordering.domain.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.core.exception.DomainException;
import org.junit.jupiter.api.Test;

class MoneyTest {

  @Test
  void ofBuildsAndExposesAmountAndCurrency() {
    Money money = Money.of(1_500, "USD");

    assertEquals(1_500, money.amountMinor());
    assertEquals("USD", money.currency());
  }

  @Test
  void rejectsNegativeAmount() {
    assertThrows(DomainException.class, () -> Money.of(-1, "USD"));
  }

  @Test
  void allowsZeroAmount() {
    assertEquals(0, Money.of(0, "USD").amountMinor());
  }

  @Test
  void rejectsNullOrBlankCurrency() {
    assertThrows(DomainException.class, () -> Money.of(1, null));
    assertThrows(DomainException.class, () -> Money.of(1, " "));
  }

  /**
   * "usd" and "USD" would be two different currencies to requireSameCurrency, and "XYZ" is not a
   * currency at all — an arbitrary non-blank string is not a currency identity. Validated against
   * ISO 4217 rather than normalised: a caller whose code differs by case has a bug better surfaced
   * than silently absorbed.
   */
  @Test
  void rejectsANonIso4217CurrencyCode() {
    assertThrows(DomainException.class, () -> Money.of(1, "usd"));
    assertThrows(DomainException.class, () -> Money.of(1, "XYZ"));
    assertThrows(DomainException.class, () -> Money.of(1, "US"));
  }

  @Test
  void plusAddsAmountsOfTheSameCurrency() {
    assertEquals(Money.of(300, "USD"), Money.of(100, "USD").plus(Money.of(200, "USD")));
  }

  @Test
  void plusRejectsADifferentCurrency() {
    DomainException ex =
        assertThrows(DomainException.class, () -> Money.of(100, "USD").plus(Money.of(200, "EUR")));
    assertTrue(ex.getMessage().contains("currency mismatch"));
  }

  @Test
  void timesMultipliesByANonNegativeFactor() {
    assertEquals(Money.of(600, "USD"), Money.of(200, "USD").times(3));
    assertEquals(Money.of(0, "USD"), Money.of(200, "USD").times(0));
  }

  @Test
  void timesRejectsANegativeFactor() {
    assertThrows(DomainException.class, () -> Money.of(200, "USD").times(-1));
  }

  @Test
  void lessThanOrEqualComparesSameCurrency() {
    assertTrue(Money.of(100, "USD").lessThanOrEqual(Money.of(100, "USD")), "equal is <=");
    assertTrue(Money.of(99, "USD").lessThanOrEqual(Money.of(100, "USD")));
    assertFalse(Money.of(101, "USD").lessThanOrEqual(Money.of(100, "USD")));
  }

  @Test
  void lessThanOrEqualRejectsADifferentCurrency() {
    assertThrows(
        DomainException.class, () -> Money.of(100, "USD").lessThanOrEqual(Money.of(100, "EUR")));
  }

  // ---------- overflow ----------

  /**
   * The assertions check the message, not just the exception type, and that is the point. Before
   * the fix an overflowing addition wrapped to a negative number and the constructor rejected it
   * with "amount must be >= 0" — so a bare {@code assertThrows(DomainException.class, ...)} passed
   * while the defect was fully present. A test that cannot tell the two refusals apart is a test
   * that certifies the bug.
   */
  @Test
  void additionRefusesToOverflowRatherThanWrappingAround() {
    Money huge = Money.of(Long.MAX_VALUE, "USD");

    DomainException overflow =
        assertThrows(DomainException.class, () -> huge.plus(Money.of(1, "USD")));

    assertEquals(
        OrderingErrorCode.AMOUNT_OVERFLOW,
        overflow.errorCode().orElse(null),
        () -> "must be reported as overflow, not as a negative amount: " + overflow.getMessage());
  }

  @Test
  void multiplicationRefusesToOverflowRatherThanWrappingAround() {
    Money half = Money.of(Long.MAX_VALUE / 2, "USD");

    DomainException overflow = assertThrows(DomainException.class, () -> half.times(4));

    assertEquals(OrderingErrorCode.AMOUNT_OVERFLOW, overflow.errorCode().orElse(null));
  }

  /** The wrap-around that used to be silent: a positive, plausible, wrong answer. */
  @Test
  void aMultiplicationThatWouldWrapToAPositiveNumberIsRefused() {
    // (2^63 / 3) * 4 wraps to a positive value well inside "non-negative", so the constructor
    // would have accepted it and the caller would have been handed a smaller number than it asked
    // for, with nothing to indicate anything had happened.
    Money third = Money.of(Long.MAX_VALUE / 3, "USD");

    DomainException overflow = assertThrows(DomainException.class, () -> third.times(4));

    assertEquals(OrderingErrorCode.AMOUNT_OVERFLOW, overflow.errorCode().orElse(null));
  }

  @Test
  void arithmeticWellInsideTheRangeIsUnaffected() {
    assertEquals(300, Money.of(100, "USD").times(3).amountMinor());
    assertEquals(150, Money.of(100, "USD").plus(Money.of(50, "USD")).amountMinor());
  }
}
