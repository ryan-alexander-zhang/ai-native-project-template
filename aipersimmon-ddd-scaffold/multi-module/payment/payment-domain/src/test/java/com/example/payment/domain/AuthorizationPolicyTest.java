package com.example.payment.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AuthorizationPolicyTest {

  private final AuthorizationPolicy policy = new AuthorizationPolicy();

  @Test
  void authorizesAmountBelowTheCeiling() {
    PaymentDecision decision = policy.decide(10_000L, "USD");

    assertTrue(decision.isAuthorized());
    assertInstanceOf(PaymentDecision.Authorized.class, decision);
  }

  @Test
  void authorizesAmountExactlyAtTheCeiling() {
    PaymentDecision decision =
        policy.decide(AuthorizationPolicy.AUTHORISATION_CEILING_MINOR, "USD");

    assertTrue(decision.isAuthorized(), "the ceiling itself is authorised (<=)");
  }

  @Test
  void declinesAmountJustAboveTheCeiling_withCodeAndReason() {
    long amount = AuthorizationPolicy.AUTHORISATION_CEILING_MINOR + 1;

    PaymentDecision decision = policy.decide(amount, "EUR");

    assertFalse(decision.isAuthorized());
    PaymentDecision.Declined declined = assertInstanceOf(PaymentDecision.Declined.class, decision);
    assertEquals(AuthorizationPolicy.DECLINE_CODE, declined.code());
    assertEquals("amount " + amount + " EUR exceeds the authorisation ceiling", declined.reason());
  }
}
