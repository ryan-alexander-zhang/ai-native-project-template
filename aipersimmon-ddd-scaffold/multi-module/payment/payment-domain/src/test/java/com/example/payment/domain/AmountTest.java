package com.example.payment.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.aipersimmon.ddd.core.exception.DomainException;
import org.junit.jupiter.api.Test;

class AmountTest {

  @Test
  void exposesItsComponents() {
    Amount amount = new Amount(1_500, "USD");

    assertEquals(1_500, amount.amountMinor());
    assertEquals("USD", amount.currency());
  }

  /** Zero is a legal amount — a fully discounted basket is a real order. */
  @Test
  void allowsZero() {
    assertEquals(0, new Amount(0, "USD").amountMinor());
  }

  @Test
  void rejectsANegativeAmount() {
    assertThrows(DomainException.class, () -> new Amount(-1, "USD"));
  }

  @Test
  void rejectsAMissingOrNonIsoCurrency() {
    assertThrows(DomainException.class, () -> new Amount(1, null));
    assertThrows(DomainException.class, () -> new Amount(1, " "));
    assertThrows(DomainException.class, () -> new Amount(1, "usd"));
    assertThrows(DomainException.class, () -> new Amount(1, "XYZ"));
  }
}
