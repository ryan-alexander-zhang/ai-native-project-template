package com.acme.samples.s2.shared;

/** Shared value object over a primitive amount; validated on construction. */
public record Money(long amountMinor, String currency) {
    public Money {
        if (amountMinor < 0) throw new IllegalArgumentException("amount must be >= 0");
        if (currency == null || currency.isBlank()) throw new IllegalArgumentException("currency required");
    }

    public static Money usd(long amountMinor) {
        return new Money(amountMinor, "USD");
    }

    public Money plus(Money other) {
        if (!currency.equals(other.currency)) throw new IllegalArgumentException("currency mismatch");
        return new Money(amountMinor + other.amountMinor, currency);
    }

    public boolean lessThanOrEqual(Money other) {
        return this.amountMinor <= other.amountMinor;
    }
}
