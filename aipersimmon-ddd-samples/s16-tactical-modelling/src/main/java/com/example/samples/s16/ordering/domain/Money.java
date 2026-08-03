package com.example.samples.s16.ordering.domain;

import com.aipersimmon.ddd.core.annotation.ValueObject;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * An amount of money: measured, not tracked, so a value object. Two instances of the same currency
 * and amount are the same money, and nothing about "which one" is meaningful.
 */
@ValueObject
public record Money(String currency, BigDecimal amount) {

  private static final int SCALE = 2;

  public Money {
    if (currency == null || currency.length() != 3) {
      throw new IllegalArgumentException("currency must be a 3-letter code: " + currency);
    }
    if (amount == null) {
      throw new IllegalArgumentException("amount must not be null");
    }
    if (amount.signum() < 0) {
      throw new IllegalArgumentException("amount must not be negative: " + amount);
    }
    // Normalising the scale is not cosmetic. A record's equality delegates to BigDecimal.equals,
    // which compares scale as well as value, so 10 and 10.00 would be different money.
    amount = amount.setScale(SCALE, RoundingMode.HALF_UP);
  }

  public static Money of(String currency, String amount) {
    return new Money(currency, new BigDecimal(amount));
  }

  public static Money zero(String currency) {
    return new Money(currency, BigDecimal.ZERO);
  }

  public Money plus(Money other) {
    requireSameCurrency(other);
    return new Money(currency, amount.add(other.amount));
  }

  public Money times(int factor) {
    if (factor < 0) {
      throw new IllegalArgumentException("factor must not be negative: " + factor);
    }
    return new Money(currency, amount.multiply(BigDecimal.valueOf(factor)));
  }

  public Money percent(int percent) {
    if (percent < 0 || percent > 100) {
      throw new IllegalArgumentException("percent must be between 0 and 100: " + percent);
    }
    return new Money(
        currency, amount.multiply(BigDecimal.valueOf(percent)).divide(BigDecimal.valueOf(100)));
  }

  public boolean isGreaterThan(Money other) {
    requireSameCurrency(other);
    return amount.compareTo(other.amount) > 0;
  }

  /** A trivial one-off guard, so an inline throw rather than an {@code Invariant}. */
  private void requireSameCurrency(Money other) {
    if (!currency.equals(other.currency)) {
      throw new IllegalArgumentException(
          "cannot combine " + currency + " with " + other.currency);
    }
  }
}
