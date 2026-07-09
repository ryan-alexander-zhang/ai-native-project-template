package com.example.ordering.domain.shared;

import com.aipersimmon.ddd.core.annotation.ValueObject;
import com.aipersimmon.ddd.core.exception.DomainException;

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
    }

    public static Money of(long amountMinor, String currency) {
        return new Money(amountMinor, currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(amountMinor + other.amountMinor, currency);
    }

    public Money times(int factor) {
        if (factor < 0) {
            throw new DomainException("factor must be >= 0");
        }
        return new Money(amountMinor * factor, currency);
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
