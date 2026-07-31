package com.example.payment.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AuthorizationPolicyTest {

  private final AuthorizationPolicy policy = new CeilingAuthorizationPolicy();

  @Test
  void authorizesAmountBelowTheCeiling() {
    PaymentDecision decision = policy.decide(new Amount(10_000L, "USD"));

    assertTrue(decision.isAuthorized());
    assertInstanceOf(PaymentDecision.Authorized.class, decision);
  }

  @Test
  void authorizesAmountExactlyAtTheCeiling() {
    PaymentDecision decision =
        policy.decide(new Amount(CeilingAuthorizationPolicy.DEFAULT_CEILING_MINOR, "USD"));

    assertTrue(decision.isAuthorized(), "the ceiling itself is authorised (<=)");
  }

  @Test
  void authorizesAZeroAmountOutright() {
    // A gift or fully discounted order reaches payment with nothing to charge. It is authorised
    // by its own branch, not by happening to sit under the ceiling — see issue-00075.
    PaymentDecision decision = policy.decide(new Amount(0L, "USD"));

    assertTrue(
        decision.isAuthorized(), "there is nothing to charge, so there is nothing to refuse");
    assertInstanceOf(PaymentDecision.Authorized.class, decision);
  }

  @Test
  void declinesAmountJustAboveTheCeiling_withCodeAndReason() {
    long amount = CeilingAuthorizationPolicy.DEFAULT_CEILING_MINOR + 1;

    PaymentDecision decision = policy.decide(new Amount(amount, "EUR"));

    assertFalse(decision.isAuthorized());
    PaymentDecision.Declined declined = assertInstanceOf(PaymentDecision.Declined.class, decision);
    assertEquals(CeilingAuthorizationPolicy.DECLINE_CODE, declined.code());
    assertEquals("amount " + amount + " EUR exceeds the authorisation ceiling", declined.reason());
  }

  /**
   * The ceiling is a constructor argument, so the boundary has to move with it. Without this the
   * claim that a deployment can set {@code payment.authorization.ceiling-minor} rests on nothing:
   * every other test here uses the default, so a policy that ignored its argument and read the
   * constant would pass all of them.
   */
  @Test
  void honoursACeilingOtherThanTheDefault() {
    AuthorizationPolicy strict = new CeilingAuthorizationPolicy(100L);

    assertTrue(strict.decide(new Amount(100L, "USD")).isAuthorized(), "at the configured ceiling");
    assertFalse(
        strict.decide(new Amount(101L, "USD")).isAuthorized(), "one above the configured ceiling");
    assertFalse(
        strict
            .decide(new Amount(CeilingAuthorizationPolicy.DEFAULT_CEILING_MINOR, "USD"))
            .isAuthorized(),
        "the default ceiling must no longer apply once one is configured");
  }

  /**
   * A negative ceiling is rejected at construction rather than at the first decision. It would
   * decline every payment — including the zero-amount ones the policy authorises by design — so the
   * failure belongs at startup, where it is one clear message, not spread across every order.
   */
  @Test
  void refusesANegativeCeiling() {
    IllegalArgumentException refused =
        assertThrows(IllegalArgumentException.class, () -> new CeilingAuthorizationPolicy(-1L));

    assertTrue(refused.getMessage().contains("-1"), "the message should name the rejected value");
  }
}
