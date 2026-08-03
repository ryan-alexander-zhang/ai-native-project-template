package com.example.samples.s17.ordering.domain;

import com.aipersimmon.ddd.core.annotation.ValueObject;

/**
 * Flattened into two columns when it is persisted ({@code currency}, {@code amount_cents}) rather than
 * serialised. Two columns are queryable, sortable and indexable; a JSON blob is none of those. Compare
 * with {@link ShippingAddress}, which goes the other way.
 */
@ValueObject
public record Money(String currency, long amountCents) {

  public Money {
    if (currency == null || currency.length() != 3) {
      throw new IllegalArgumentException("currency must be a 3-letter code: " + currency);
    }
    if (amountCents < 0) {
      throw new IllegalArgumentException("amount must not be negative: " + amountCents);
    }
  }

  public static Money of(String currency, long amountCents) {
    return new Money(currency, amountCents);
  }

  public Money plus(Money other) {
    if (!currency.equals(other.currency)) {
      throw new IllegalArgumentException("cannot combine " + currency + " with " + other.currency);
    }
    return new Money(currency, amountCents + other.amountCents);
  }

  public Money times(int factor) {
    return new Money(currency, amountCents * factor);
  }
}
