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
}
