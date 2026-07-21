package com.example.payment.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aipersimmon.ddd.core.exception.DomainException;
import org.junit.jupiter.api.Test;

class PaymentDecisionTest {

  @Test
  void authorizedIsAuthorized() {
    assertTrue(new PaymentDecision.Authorized().isAuthorized());
  }

  @Test
  void declinedIsNotAuthorized_andCarriesCodeAndReason() {
    PaymentDecision.Declined declined =
        new PaymentDecision.Declined("payment.declined", "insufficient funds");

    assertFalse(declined.isAuthorized());
    assertEquals("payment.declined", declined.code());
    assertEquals("insufficient funds", declined.reason());
  }

  @Test
  void declinedRejectsNullCode() {
    assertThrows(DomainException.class, () -> new PaymentDecision.Declined(null, "some reason"));
  }

  @Test
  void declinedRejectsBlankCode() {
    assertThrows(DomainException.class, () -> new PaymentDecision.Declined("  ", "some reason"));
  }
}
