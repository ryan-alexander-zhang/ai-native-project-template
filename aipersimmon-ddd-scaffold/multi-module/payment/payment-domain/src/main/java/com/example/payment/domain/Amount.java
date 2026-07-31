package com.example.payment.domain;

import com.aipersimmon.ddd.core.annotation.ValueObject;
import com.aipersimmon.ddd.core.exception.DomainException;
import java.util.Currency;

/**
 * A monetary amount in minor units with its currency — payment's own value object, deliberately not
 * shared with ordering's {@code Money}.
 *
 * <p>"A published contract should stay flat" is a rule about the {@code api} integration events,
 * where a primitive pair travels well across contexts; it was never a reason for this context's
 * <em>own</em> domain port to take {@code (long, String)} and leave "is this a currency?" to every
 * implementation separately. Zero is a legal amount here (a fully discounted basket is a real
 * order), so only negatives are refused.
 */
@ValueObject
public record Amount(long amountMinor, String currency) {

  public Amount {
    if (amountMinor < 0) {
      throw new DomainException("amount must be >= 0, was " + amountMinor);
    }
    if (currency == null || currency.isBlank()) {
      throw new DomainException("currency required");
    }
    // Validated against ISO 4217 rather than normalised — a policy comparing "usd" to a ceiling
    // configured in "USD" would be reasoning about two different currencies without noticing.
    try {
      Currency.getInstance(currency);
    } catch (IllegalArgumentException unknown) {
      throw new DomainException("not an ISO 4217 currency code: " + currency);
    }
  }
}
