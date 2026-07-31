package com.example.ordering.domain.shared;

import com.aipersimmon.ddd.core.annotation.ValueObject;
import com.aipersimmon.ddd.core.exception.DomainException;
import java.util.function.LongSupplier;

/** A monetary amount in minor units (for example cents) with a currency. */
@ValueObject
public record Money(long amountMinor, String currency) {

  public Money {
    if (amountMinor < 0) {
      throw new DomainException("amount must be >= 0");
    }
    if (currency == null || currency.isBlank()) {
      throw new DomainException("currency required");
    }
    // An arbitrary non-blank string is not a currency identity: "usd" and "USD" would be two
    // currencies to requireSameCurrency, and "XYZ" none at all. Validated against ISO 4217 rather
    // than normalised — a caller whose code differs by case has a bug better surfaced than
    // silently absorbed (java.util.Currency accepts exactly the uppercase ISO spellings).
    try {
      java.util.Currency.getInstance(currency);
    } catch (IllegalArgumentException unknown) {
      throw new DomainException("not an ISO 4217 currency code: " + currency);
    }
  }

  public static Money of(long amountMinor, String currency) {
    return new Money(amountMinor, currency);
  }

  public Money plus(Money other) {
    requireSameCurrency(other);
    return new Money(exact(() -> Math.addExact(amountMinor, other.amountMinor)), currency);
  }

  /**
   * The difference, which must not go negative — the record's own guard enforces that. Callers
   * subtracting a committed amount (releasing reserved credit, say) should already know the amount
   * was committed; a negative result means their bookkeeping is wrong, and failing here is how they
   * find out rather than silently carrying a nonsensical balance.
   */
  public Money minus(Money other) {
    requireSameCurrency(other);
    if (other.amountMinor > amountMinor) {
      throw new DomainException(
          "cannot subtract " + other.amountMinor + " from " + amountMinor + " " + currency);
    }
    return new Money(amountMinor - other.amountMinor, currency);
  }

  public Money times(int factor) {
    if (factor < 0) {
      throw new DomainException("factor must be >= 0");
    }
    return new Money(exact(() -> Math.multiplyExact(amountMinor, (long) factor)), currency);
  }

  /**
   * Runs a checked arithmetic operation, turning Java's wrap-around into a refusal.
   *
   * <p>The invariant this record actually needs is "the amount is representable", and "amount >= 0"
   * in the constructor is a lossy projection of it: an addition that overflows into a positive
   * number lands squarely inside "non-negative" and is accepted as a perfectly ordinary total
   * (issue-00077). Where it overflows into a negative one the constructor does reject it — but
   * reports "amount must be >= 0", which sends the reader looking for a negative input that does
   * not exist. Both failures are worse than an explicit one.
   *
   * <p>Not an argument against {@code long} over {@code BigDecimal}: minor units in a {@code long}
   * is the right model and the one payment processors use. It only has to be arithmetic that can
   * fail.
   */
  private static long exact(LongSupplier operation) {
    try {
      return operation.getAsLong();
    } catch (ArithmeticException overflow) {
      throw new DomainException(
          OrderingErrorCode.AMOUNT_OVERFLOW, "monetary amount is too large to represent");
    }
  }

  public boolean lessThanOrEqual(Money other) {
    requireSameCurrency(other);
    return amountMinor <= other.amountMinor;
  }

  private void requireSameCurrency(Money other) {
    if (!currency.equals(other.currency)) {
      throw new DomainException("currency mismatch: " + currency + " vs " + other.currency);
    }
  }
}
