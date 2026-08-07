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

  /**
   * The third case of the sealed interface, which this test class had left to the modules
   * downstream of it. They do exercise it — {@code PaymentVoidRaceTest} drives the whole race — but
   * their coverage lands in their own module's report, so the write-side statement "a voided
   * operation never authorizes" had no test where the type that makes it lives.
   *
   * <p>What it asserts is small and load-bearing. {@code Voided} is recorded in two situations that
   * look opposite — over an {@code Authorized}, releasing the hold, and against an operation
   * nothing has decided yet, refusing one in advance — and the reason a late authorization cannot
   * slip past is that both end in a decision answering false here.
   */
  @Test
  void voidedIsNotAuthorized() {
    assertFalse(new PaymentDecision.Voided().isAuthorized());
  }
}
